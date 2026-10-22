package io.hanji;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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

    private static final int MAX_NESTING = 32;
    private int nesting;

    /**
     * Nested event loop: runs queued UI tasks until {@code done} holds or the timeout expires, so that a
     * synchronous call on the UI thread can wait for an answer from the page without blocking the UI.
     *
     * @return whether {@code done} holds
     */
    boolean pumpUntil(BooleanSupplier done, long timeoutMillis) {
        if (!isLoopThread()) {
            throw new IllegalStateException("pumpUntil must be called on the UI thread");
        }
        if (nesting >= MAX_NESTING) {
            throw new IllegalStateException("Too many nested synchronous calls");
        }
        nesting++;
        try {
            execute(afterTask); // send what the current task produced (including the request being waited for)
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (running && !done.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                Runnable task = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (task == null) {
                    continue;
                }
                execute(task);
                int guard = 0;
                while (!deferred.isEmpty() && guard++ < MAX_DEFERRED_PER_TASK) {
                    execute(deferred.poll());
                }
                execute(afterTask);
            }
            return done.getAsBoolean();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return done.getAsBoolean();
        } finally {
            nesting--;
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
