package io.hanji;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * The single Hanji UI thread. Every task is followed by the deferred tasks it scheduled and by a flush
 * of all windows, so all DOM operations of one task reach the page as one batch.
 */
final class EventLoop {

    private static final int MAX_DEFERRED_PER_TASK = 100_000;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ArrayDeque<Runnable> deferred = new ArrayDeque<>();
    private final Thread thread;
    private final Runnable afterTask;
    private final Consumer<Throwable> errorHandler;
    private volatile boolean running = true;

    EventLoop(Runnable afterTask, Consumer<Throwable> errorHandler) {
        this.afterTask = afterTask;
        this.errorHandler = errorHandler;
        this.thread = new Thread(this::run, "Hanji-UI");
    }

    void start() {
        thread.start();
    }

    boolean isLoopThread() {
        return Thread.currentThread() == thread;
    }

    void post(Runnable task) {
        queue.add(Objects.requireNonNull(task));
    }

    /** Runs {@code task} after the current UI task (before the flush), or posts it when called off the UI thread. */
    void defer(Runnable task) {
        if (isLoopThread()) {
            deferred.add(Objects.requireNonNull(task));
        } else {
            post(task);
        }
    }

    void stop() {
        running = false;
        queue.add(() -> { });
    }

    void join() {
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (running) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                break;
            }
            execute(task);
            int guard = 0;
            while (!deferred.isEmpty() && guard++ < MAX_DEFERRED_PER_TASK) {
                execute(deferred.poll());
            }
            execute(afterTask);
        }
    }

    private void execute(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            errorHandler.accept(t);
        }
    }
}
