package io.hanji.host;

import io.hanji.HanjiConfig;
import io.hanji.spi.HostCallbacks;
import io.hanji.spi.HostWindow;
import io.hanji.spi.HostWindowSpec;
import io.hanji.spi.WindowHost;
import io.hanji.spi.WindowHostProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Shows each window as a Chromium "app" window ({@code --app=URL}): a native window without tabs or
 * address bar. Works with Chrome, Edge (preinstalled on Windows 10/11), Chromium and Brave.
 * Each window runs in its own browser process with a temporary profile, so closing the window ends
 * the process and Hanji is notified immediately.
 */
public final class ChromiumAppHost implements WindowHost {

    private final Path browser;
    private final List<String> extraArgs;
    private final Set<AppWindow> windows = ConcurrentHashMap.newKeySet();

    public ChromiumAppHost(Path browser, List<String> extraArgs) {
        this.browser = browser;
        this.extraArgs = List.copyOf(extraArgs);
    }

    @Override
    public String name() {
        return "app";
    }

    @Override
    public HostWindow open(HostWindowSpec spec, HostCallbacks callbacks) throws IOException {
        Path profile = Files.createTempDirectory("hanji-app-");
        List<String> cmd = new ArrayList<>();
        cmd.add(browser.toString());
        cmd.add("--app=" + spec.url());
        cmd.add("--user-data-dir=" + profile.toAbsolutePath());
        cmd.add("--window-size=" + spec.width() + "," + spec.height());
        if (spec.x() != null && spec.y() != null) {
            cmd.add("--window-position=" + spec.x() + "," + spec.y());
        }
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-sync");
        cmd.add("--disable-extensions");
        cmd.add("--disable-default-apps");
        cmd.add("--disable-background-networking");
        cmd.add("--disable-component-update");
        cmd.add("--disable-features=Translate,MediaRouter,OptimizationHints");
        cmd.add("--password-store=basic");
        cmd.add("--use-mock-keychain");
        if (!Displays.isWindows() && !Displays.isMac() && "root".equals(System.getProperty("user.name"))) {
            cmd.add("--no-sandbox"); // Chromium refuses to start as root otherwise
            cmd.add("--test-type");  // hides the "unsupported command-line flag" bar caused by --no-sandbox
        }
        cmd.addAll(extraArgs);

        ProcessBuilder builder = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // Plain Chromium builds show a "Google API keys are missing" bar unless these are set
        builder.environment().putIfAbsent("GOOGLE_API_KEY", "no");
        builder.environment().putIfAbsent("GOOGLE_DEFAULT_CLIENT_ID", "no");
        builder.environment().putIfAbsent("GOOGLE_DEFAULT_CLIENT_SECRET", "no");
        Process process = builder.start();
        AppWindow window = new AppWindow(process, profile);
        windows.add(window);
        process.onExit().thenRun(() -> {
            windows.remove(window);
            if (!window.closing) {
                callbacks.closed();
            }
            deleteRecursively(profile);
        });
        return window;
    }

    @Override
    public void shutdown() {
        for (AppWindow w : List.copyOf(windows)) {
            w.closing = true;
            w.process.destroy();
        }
        for (AppWindow w : List.copyOf(windows)) {
            try {
                if (!w.process.waitFor(3, TimeUnit.SECONDS)) {
                    w.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                w.process.destroyForcibly();
            }
            deleteRecursively(w.profile);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // locked files (Windows) are retried below
                    }
                });
            } catch (IOException ignored) {
                // retry
            }
            if (!Files.exists(dir)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class AppWindow implements HostWindow {
        final Process process;
        final Path profile;
        volatile boolean closing;

        AppWindow(Process process, Path profile) {
            this.process = process;
            this.profile = profile;
        }

        @Override
        public void close() {
            closing = true;
            process.destroy();
            CompletableFuture.runAsync(() -> {
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /** Provider registration. */
    public static final class Provider implements WindowHostProvider {
        @Override
        public String name() {
            return "app";
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public boolean isAvailable(HanjiConfig config) {
            return Displays.hasDisplay() && ChromiumLocator.find(config.getBrowserPath()) != null;
        }

        @Override
        public WindowHost create(HanjiConfig config) {
            Path browser = ChromiumLocator.find(config.getBrowserPath());
            if (browser == null) {
                throw new IllegalStateException("No Chrome, Edge or Chromium installation found");
            }
            return new ChromiumAppHost(browser, config.getBrowserArgs());
        }
    }
}
