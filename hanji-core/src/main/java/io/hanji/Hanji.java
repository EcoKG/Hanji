package io.hanji;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Static entry points: launching, UI-thread scheduling and background work.
 * <pre>
 * Hanji.launch(window -&gt; window.setContent(new Label("Hello")).show());
 *
 * Hanji.async(() -&gt; repository.findAll())          // runs on a worker thread
 *      .thenAccept(rows -&gt; table.getItems().setAll(rows));   // continues on the UI thread
 * </pre>
 */
public final class Hanji {

    private Hanji() {
    }

    /** Runs the application class. Blocks until the application exits. */
    public static void launch(Class<? extends HanjiApplication> appClass, String... args) {
        Objects.requireNonNull(appClass, "appClass");
        HanjiRuntime.launch(HanjiRuntime.classFactory(appClass), appClass.getClassLoader(), args);
    }

    /** Runs a lambda-based application: {@code start} receives the primary window. Blocks until exit. */
    public static void launch(Consumer<Window> start) {
        launch(new String[0], start);
    }

    public static void launch(String[] args, Consumer<Window> start) {
        Objects.requireNonNull(start, "start");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        HanjiRuntime.launch(() -> new HanjiApplication() {
            @Override
            public void start(Window window) {
                start.accept(window);
            }
        }, loader, args);
    }

    /** Runs {@code task} on the UI thread later. Safe to call from any thread. */
    public static void runLater(Runnable task) {
        HanjiRuntime.get().post(Objects.requireNonNull(task));
    }

    /**
     * Runs {@code task} at the end of the current UI task (before changes are sent to the page).
     * Useful to coalesce repeated work such as re-rendering. Outside the UI thread this equals
     * {@link #runLater(Runnable)}; when Hanji is not running the task runs immediately.
     */
    public static void defer(Runnable task) {
        HanjiRuntime rt = HanjiRuntime.peek();
        if (rt == null) {
            task.run();
        } else {
            rt.loop.defer(task);
        }
    }

    public static boolean isUiThread() {
        HanjiRuntime rt = HanjiRuntime.peek();
        return rt != null && rt.isUiThread();
    }

    public static boolean isRunning() {
        return HanjiRuntime.peek() != null;
    }

    /**
     * Runs {@code task} on a worker thread (virtual threads on Java 21+). The returned future completes
     * on the UI thread, so dependent stages ({@code thenAccept}, ...) may update the UI directly.
     */
    public static <T> CompletableFuture<T> async(Callable<T> task) {
        HanjiRuntime rt = HanjiRuntime.get();
        CompletableFuture<T> future = new CompletableFuture<>();
        rt.workers.execute(() -> {
            try {
                T value = task.call();
                rt.post(() -> future.complete(value));
            } catch (Throwable t) {
                rt.post(() -> future.completeExceptionally(t));
            }
        });
        return future;
    }

    /** Closes all windows, calls {@link HanjiApplication#stop()} and ends {@code launch}. */
    public static void exit() {
        HanjiRuntime rt = HanjiRuntime.peek();
        if (rt != null) {
            rt.requestExit();
        }
    }

    public static HanjiConfig config() {
        return HanjiRuntime.get().config;
    }

    /** Open (not yet closed) windows. UI thread only. */
    public static List<Window> windows() {
        HanjiRuntime rt = HanjiRuntime.get();
        rt.checkUiThread();
        return rt.windows();
    }
}
