package io.hanji.electron;

import io.hanji.HanjiConfig;
import io.hanji.WindowState;
import io.hanji.host.Displays;
import io.hanji.json.Json;
import io.hanji.spi.HostCallbacks;
import io.hanji.spi.HostTray;
import io.hanji.spi.HostWindow;
import io.hanji.spi.HostWindowSpec;
import io.hanji.spi.WindowHost;
import io.hanji.spi.WindowHostProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows windows with a bundled Electron runtime (the way VS Code and Discord ship Chromium): no installed
 * browser is needed, and windows get native controls — size and state at runtime, menu bars, tray icons and
 * close confirmation. One Electron process hosts all windows of the application; it ends with the JVM.
 */
public final class ElectronHost implements WindowHost {

    private static final String[] APP_FILES = {"package.json", "main.js", "preload.js", "tray.png", "tray@2x.png"};

    private final Path electron;
    private final HanjiConfig config;
    private final String token;
    private final Map<Integer, ElectronWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, ElectronTray> trays = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private final Object writeLock = new Object();

    private Process process;
    private Socket socket;
    private Writer out;
    private Path appDir;
    private volatile boolean shuttingDown;

    public ElectronHost(Path electron, HanjiConfig config) {
        this.electron = electron;
        this.config = config;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.token = HexFormat.of().formatHex(bytes);
    }

    @Override
    public String name() {
        return "electron";
    }

    // ------------------------------------------------------------------ process

    private synchronized void ensureStarted() throws IOException {
        if (process != null && process.isAlive() && socket != null && !socket.isClosed()) {
            return;
        }
        if (appDir == null) {
            appDir = extractApp();
        }
        boolean linux = !Displays.isWindows() && !Displays.isMac();
        boolean noSandbox = linux && "root".equals(System.getProperty("user.name"));
        try {
            start(noSandbox);
        } catch (SandboxUnavailable e) {
            // e.g. Ubuntu 24.04 restricts the user namespaces Chromium's sandbox needs, unless the
            // application is installed with a matching AppArmor profile or a setuid chrome-sandbox
            System.err.println("[hanji] WARNING: Electron's sandbox is not available on this system; starting "
                    + "without it. Packaged applications should install chrome-sandbox setuid root or an AppArmor profile.");
            start(true);
        }
    }

    private void start(boolean noSandbox) throws IOException {
        synchronized (stderrTail) {
            stderrTail.clear();
        }
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(250);
            List<String> cmd = new ArrayList<>();
            cmd.add(electron.toString());
            cmd.add(appDir.toString());
            cmd.add("--hanji-control=127.0.0.1:" + server.getLocalPort());
            cmd.add("--hanji-token=" + token);
            cmd.add("--hanji-name=" + config.getAppName());
            if (config.isDevMode()) {
                cmd.add("--hanji-dev");
            }
            if (noSandbox) {
                cmd.add("--no-sandbox");
            }
            cmd.addAll(config.getElectronArgs());
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().remove("ELECTRON_RUN_AS_NODE"); // would turn Electron into plain Node.js
            Process p = builder.start();
            Thread errors = new Thread(() -> drainStderr(p.getErrorStream()), "Hanji-Electron-stderr");
            errors.setDaemon(true);
            errors.start();

            long deadline = System.currentTimeMillis() + 30_000;
            Socket s = null;
            while (s == null) {
                try {
                    s = server.accept();
                } catch (SocketTimeoutException waiting) {
                    if (!p.isAlive()) {
                        String tail = stderrText();
                        if (!noSandbox && tail.toLowerCase(Locale.ROOT).contains("sandbox")) {
                            throw new SandboxUnavailable();
                        }
                        throw new IOException("Electron exited with code " + p.exitValue() + " during startup:\n" + tail);
                    }
                    if (System.currentTimeMillis() > deadline) {
                        p.destroyForcibly();
                        throw new IOException("Electron did not start within 30 s:\n" + stderrText());
                    }
                }
            }
            s.setTcpNoDelay(true);
            s.setSoTimeout(15_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Object hello = Json.parse(String.valueOf(in.readLine()));
            if (!(hello instanceof Map) || !token.equals(((Map<?, ?>) hello).get("token"))) {
                s.close();
                p.destroyForcibly();
                throw new IOException("Unexpected handshake from Electron");
            }
            s.setSoTimeout(0);
            debug("Electron " + ((Map<?, ?>) hello).get("electron") + " connected");
            process = p;
            socket = s;
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> readEvents(in), "Hanji-Electron");
            reader.setDaemon(true);
            reader.start();
            p.onExit().thenRun(() -> processExited(p));
        }
    }

    private void drainStderr(InputStream stream) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > 60) {
                        stderrTail.removeFirst();
                    }
                }
                debug("electron: " + line);
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private String stderrText() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }

    private void processExited(Process p) {
        if (p != process) {
            return;
        }
        if (!shuttingDown) {
            if (p.exitValue() != 0) {
                System.err.println("[hanji] Electron exited unexpectedly (code " + p.exitValue() + "):\n" + stderrText());
            }
            // the user quit (e.g. Cmd+Q) or Electron crashed: every window is gone
            for (ElectronWindow w : List.copyOf(windows.values())) {
                windows.remove(w.id);
                w.callbacks.closed();
            }
        }
    }

    private Path extractApp() throws IOException {
        Path dir = Files.createTempDirectory("hanji-electron-");
        for (String name : APP_FILES) {
            try (InputStream in = ElectronHost.class.getResourceAsStream("/io/hanji/electron/app/" + name)) {
                if (in == null) {
                    throw new IOException("Missing resource " + name);
                }
                Files.copy(in, dir.resolve(name));
            }
        }
        return dir;
    }

    // ------------------------------------------------------------------ messages

    void send(Map<String, Object> message) {
        String line = Json.stringify(message);
        synchronized (writeLock) {
            if (out == null) {
                return;
            }
            try {
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                debug("Electron control channel closed: " + e.getMessage());
            }
        }
    }

    private void readEvents(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    Object parsed = Json.parse(line);
                    if (parsed instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = (Map<String, Object>) parsed;
                        dispatch(event);
                    }
                } catch (RuntimeException e) {
                    System.err.println("[hanji] Bad message from Electron: " + e);
                }
            }
        } catch (IOException ignored) {
            // channel closed; processExited handles the windows
        }
    }

    private void dispatch(Map<String, Object> e) {
        String ev = String.valueOf(e.get("ev"));
        ElectronWindow w = e.get("win") instanceof Number ? windows.get(((Number) e.get("win")).intValue()) : null;
        switch (ev) {
            case "window":
                if (w != null) {
                    Map<?, ?> b = (Map<?, ?>) e.get("bounds");
                    w.callbacks.geometryChanged(num(b.get("x")), num(b.get("y")), num(b.get("width")), num(b.get("height")),
                            WindowState.valueOf(String.valueOf(e.get("state"))), Boolean.TRUE.equals(e.get("focused")));
                }
                break;
            case "close-request":
                if (w != null) {
                    w.callbacks.closeRequested();
                }
                break;
            case "closed":
                if (w != null) {
                    windows.remove(w.id);
                    if (!w.closing) {
                        w.callbacks.closed();
                    }
                }
                break;
            case "menu": {
                Boolean checked = e.get("checked") instanceof Boolean ? (Boolean) e.get("checked") : null;
                String id = String.valueOf(e.get("id"));
                if (w != null) {
                    w.callbacks.menuAction(id, checked);
                } else if (e.get("tray") != null) {
                    ElectronTray t = trays.get(String.valueOf(e.get("tray")));
                    if (t != null) {
                        t.callbacks.menuAction(id, checked);
                    }
                }
                break;
            }
            case "tray-click": {
                ElectronTray t = trays.get(String.valueOf(e.get("tray")));
                if (t != null) {
                    t.callbacks.clicked();
                }
                break;
            }
            case "error":
                System.err.println("[hanji] Electron: " + e.get("message"));
                break;
            default:
                debug("Unknown Electron event " + ev);
        }
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static Map<String, Object> message(String cmd, Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cmd", cmd);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** Sends a test command to the Electron shell (click a menu item or the tray, close a window as the user). */
    public void sendTestCommand(Map<String, Object> command) {
        Map<String, Object> m = new LinkedHashMap<>(command);
        m.put("cmd", "test");
        send(m);
    }

    // ------------------------------------------------------------------ WindowHost

    @Override
    public HostWindow open(HostWindowSpec spec, HostCallbacks callbacks) throws IOException {
        ensureStarted();
        ElectronWindow w = new ElectronWindow(ids.incrementAndGet(), callbacks);
        windows.put(w.id, w);
        send(message("open", "win", w.id, "url", spec.url().toString(), "title", spec.title(),
                "width", spec.width(), "height", spec.height(), "x", spec.x(), "y", spec.y()));
        return w;
    }

    @Override
    public HostTray createTray(HostTray.Callbacks callbacks) throws IOException {
        ensureStarted();
        ElectronTray t = new ElectronTray("t" + ids.incrementAndGet(), callbacks);
        trays.put(t.id, t);
        return t;
    }

    @Override
    public void shutdown() {
        shuttingDown = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            send(message("quit"));
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroy();
                    if (!p.waitFor(3, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        if (appDir != null) {
            ElectronRuntime.deleteRecursively(appDir);
        }
    }

    private void debug(String message) {
        if (Boolean.getBoolean("hanji.debug")) {
            System.err.println("[hanji] " + message);
        }
    }

    private static final class SandboxUnavailable extends IOException {
        private static final long serialVersionUID = 1L;
    }

    // ------------------------------------------------------------------ windows & trays

    private final class ElectronWindow implements HostWindow {
        final int id;
        final HostCallbacks callbacks;
        volatile boolean closing;

        ElectronWindow(int id, HostCallbacks callbacks) {
            this.id = id;
            this.callbacks = callbacks;
        }

        @Override
        public void close() {
            closing = true;
            send(message("close", "win", id));
        }

        @Override
        public boolean closesWithPage() {
            return false;
        }

        @Override
        public boolean supportsNativeControls() {
            return true;
        }

        @Override
        public void setBounds(Integer x, Integer y, Integer width, Integer height) {
            send(message("bounds", "win", id, "x", x, "y", y, "width", width, "height", height));
        }

        @Override
        public void center() {
            send(message("center", "win", id));
        }

        @Override
        public void setState(WindowState state) {
            send(message("state", "win", id, "state", state.name()));
        }

        @Override
        public void toFront() {
            send(message("state", "win", id, "state", "FRONT"));
        }

        @Override
        public void setResizable(boolean resizable) {
            send(message("set", "win", id, "prop", "resizable", "value", resizable));
        }

        @Override
        public void setAlwaysOnTop(boolean alwaysOnTop) {
            send(message("set", "win", id, "prop", "alwaysOnTop", "value", alwaysOnTop));
        }

        @Override
        public void setMinimumSize(int width, int height) {
            send(message("set", "win", id, "prop", "minimumSize", "value", List.of(width, height)));
        }

        @Override
        public void setCloseGuard(boolean enabled) {
            send(message("set", "win", id, "prop", "closeGuard", "value", enabled));
        }

        @Override
        public void setMenu(List<Map<String, Object>> menus) {
            send(message("menu", "win", id, "menus", menus));
        }
    }

    private final class ElectronTray implements HostTray {
        final String id;
        final HostTray.Callbacks callbacks;

        ElectronTray(String id, HostTray.Callbacks callbacks) {
            this.id = id;
            this.callbacks = callbacks;
        }

        @Override
        public void update(String iconPath, String tooltip, List<Map<String, Object>> items) {
            send(message("tray", "action", "update", "id", id, "icon", iconPath, "tooltip", tooltip, "items", items));
        }

        @Override
        public void remove() {
            trays.remove(id);
            send(message("tray", "action", "remove", "id", id));
        }
    }

    // ------------------------------------------------------------------ provider

    /** Registers the host as {@code electron}; preferred in auto mode whenever a display is available. */
    public static final class Provider implements WindowHostProvider {
        @Override
        public String name() {
            return "electron";
        }

        @Override
        public int priority() {
            return 80;
        }

        @Override
        public boolean isAvailable(HanjiConfig config) {
            return Displays.hasDisplay()
                    && (ElectronRuntime.findInstalled(config) != null || config.isElectronDownload());
        }

        @Override
        public WindowHost create(HanjiConfig config) throws IOException {
            Path exe = ElectronRuntime.resolve(config);
            if (exe == null) {
                throw new IOException("Electron is not installed and downloading is disabled (hanji.electronDownload=false)");
            }
            return new ElectronHost(exe, config);
        }
    }
}
