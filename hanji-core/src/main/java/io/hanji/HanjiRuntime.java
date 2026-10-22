package io.hanji;

import io.hanji.host.ExternalHost;
import io.hanji.spi.WindowHost;
import io.hanji.spi.WindowHostProvider;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** The running application: UI thread, bridge server, window host and windows. */
final class HanjiRuntime {

    private static final Object LOCK = new Object();
    private static volatile HanjiRuntime current;

    final HanjiConfig config;
    final EventLoop loop;
    final ExecutorService workers;
    final ScheduledExecutorService scheduler;
    private final Map<String, Window> windowsByToken = new ConcurrentHashMap<>();
    private final List<Window> windows = new ArrayList<>();
    private ClassLoader resourceLoader = HanjiRuntime.class.getClassLoader();
    private BridgeServer server;
    private WindowHost host;
    private HanjiApplication app;
    private boolean shuttingDown;
    private Thread shutdownHook;

    private HanjiRuntime(HanjiConfig config) {
        this.config = config;
        this.loop = new EventLoop(this::flushWindows, this::reportError);
        this.workers = newWorkerPool();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("Hanji-Timer"));
    }

    static HanjiRuntime get() {
        HanjiRuntime rt = current;
        if (rt == null) {
            throw new IllegalStateException("Hanji is not running. Start the application with Hanji.launch(...)");
        }
        return rt;
    }

    static HanjiRuntime peek() {
        return current;
    }

    /** Starts the runtime, runs the application and blocks until it exits. */
    static void launch(Supplier<? extends HanjiApplication> appFactory, ClassLoader loader, String[] args) {
        HanjiRuntime rt;
        synchronized (LOCK) {
            if (current != null) {
                throw new IllegalStateException("A Hanji application is already running");
            }
            rt = new HanjiRuntime(HanjiConfig.fromSystemProperties());
            if (loader != null) {
                rt.resourceLoader = loader;
            }
            current = rt;
        }
        try {
            rt.loop.start();
            rt.loop.post(() -> rt.startup(appFactory, args));
            rt.loop.join();
        } finally {
            rt.cleanup();
            synchronized (LOCK) {
                current = null;
            }
        }
    }

    static Supplier<HanjiApplication> classFactory(Class<? extends HanjiApplication> appClass) {
        return () -> {
            try {
                Constructor<? extends HanjiApplication> c = appClass.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot instantiate " + appClass.getName()
                        + " (it needs a no-argument constructor)", e);
            }
        };
    }

    private void startup(Supplier<? extends HanjiApplication> appFactory, String[] args) {
        try {
            app = appFactory.get();
            app.parameters = List.of(args);
            app.configure(config);
            if (Boolean.getBoolean("hanji.debug")) {
                Log.setDebug(true);
            }
            app.init();
            server = new BridgeServer(this);
            server.start(config.getPort());
            host = selectHost();
            Log.debug("Server " + server.baseUri() + ", host '" + host.name() + "'");
            installShutdownHook();
            Window primary = new Window();
            app.start(primary);
        } catch (Throwable t) {
            reportError(t);
            shutdown();
        }
    }

    private WindowHost selectHost() throws Exception {
        List<WindowHostProvider> providers = new ArrayList<>();
        for (ServiceLoader.Provider<WindowHostProvider> p
                : ServiceLoader.load(WindowHostProvider.class, resourceLoader).stream().collect(Collectors.toList())) {
            try {
                providers.add(p.get());
            } catch (ServiceConfigurationError e) {
                Log.warn("Ignoring broken window host provider: " + e.getMessage());
            }
        }
        String wanted = config.getHost();
        if (!"auto".equalsIgnoreCase(wanted)) {
            for (WindowHostProvider p : providers) {
                if (p.name().equalsIgnoreCase(wanted)) {
                    if (!p.isAvailable(config)) {
                        throw new IllegalStateException("Window host '" + wanted + "' is not available on this system"
                                + ("app".equalsIgnoreCase(wanted)
                                ? " (no Chrome/Edge/Chromium found; set -Dhanji.browser=/path/to/browser)" : ""));
                    }
                    return p.create(config);
                }
            }
            throw new IllegalStateException("Unknown window host '" + wanted + "'. Available: "
                    + providers.stream().map(WindowHostProvider::name).collect(Collectors.joining(", ")));
        }
        providers.sort(Comparator.comparingInt(WindowHostProvider::priority).reversed());
        for (WindowHostProvider p : providers) {
            try {
                if (p.isAvailable(config)) {
                    return p.create(config);
                }
            } catch (Exception e) {
                Log.warn("Window host '" + p.name() + "' failed to start: " + e.getMessage());
            }
        }
        return new ExternalHost();
    }

    // ------------------------------------------------------------------ windows

    void register(Window w) {
        windowsByToken.put(w.token(), w);
        windows.add(w);
    }

    Window windowByToken(String token) {
        return windowsByToken.get(token);
    }

    List<Window> windows() {
        return List.copyOf(windows);
    }

    void windowClosed(Window w, boolean allowImplicitExit) {
        windowsByToken.remove(w.token());
        windows.remove(w);
        if (allowImplicitExit && !shuttingDown && config.isImplicitExit()
                && windows.stream().noneMatch(Window::isShowing)) {
            shutdown();
        }
    }

    private void flushWindows() {
        for (Window w : windows) {
            w.flush();
        }
    }

    void scheduleConnectionCheck(Window w) {
        try {
            scheduler.schedule(() -> post(w::checkConnection), config.getCloseGraceMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutting down
        }
    }

    // ------------------------------------------------------------------ threads

    boolean isUiThread() {
        return loop.isLoopThread();
    }

    void checkUiThread() {
        if (!loop.isLoopThread()) {
            throw new IllegalStateException("Not on the Hanji UI thread (current thread: "
                    + Thread.currentThread().getName() + "). Use Hanji.runLater(...) to update the UI.");
        }
    }

    void post(Runnable task) {
        loop.post(task);
    }

    void reportError(Throwable t) {
        try {
            config.getErrorHandler().accept(t);
        } catch (Throwable inner) {
            t.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ accessors

    URI baseUri() {
        if (server == null) {
            throw new IllegalStateException("The Hanji server is not started yet (create windows in start())");
        }
        return server.baseUri();
    }

    WindowHost host() {
        return host;
    }

    ClassLoader resourceLoader() {
        return resourceLoader;
    }

    String text(String key) {
        boolean ko = "ko".equals(config.getLocale().getLanguage());
        switch (key) {
            case "ok": return ko ? "확인" : "OK";
            case "cancel": return ko ? "취소" : "Cancel";
            case "closed": return ko ? "이 창은 닫아도 됩니다." : "This window can be closed.";
            default: return key;
        }
    }

    // ------------------------------------------------------------------ shutdown

    void requestExit() {
        loop.post(this::shutdown);
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        for (Window w : List.copyOf(windows)) {
            w.closeForShutdown();
        }
        if (app != null) {
            try {
                app.stop();
            } catch (Throwable t) {
                reportError(t);
            }
        }
        loop.stop();
    }

    private void cleanup() {
        if (host != null) {
            try {
                host.shutdown();
            } catch (Throwable t) {
                reportError(t);
            }
        }
        if (server != null) {
            server.stop();
        }
        workers.shutdownNow();
        scheduler.shutdownNow();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
        }
    }

    private void installShutdownHook() {
        WindowHost h = host;
        shutdownHook = new Thread(() -> {
            try {
                h.shutdown();
            } catch (Throwable ignored) {
                // best effort
            }
        }, "Hanji-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    // ------------------------------------------------------------------ helpers

    static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Virtual threads on Java 21+, a cached daemon pool otherwise. */
    private static ExecutorService newWorkerPool() {
        try {
            return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (ReflectiveOperationException e) {
            return Executors.newCachedThreadPool(daemonFactory("Hanji-Worker"));
        }
    }
}
