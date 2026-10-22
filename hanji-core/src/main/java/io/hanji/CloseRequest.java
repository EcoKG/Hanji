package io.hanji;

/**
 * The user asked to close a window (see {@link Window#setOnCloseRequest}). Call {@link #cancel()} to keep the
 * window open, e.g. to ask about unsaved changes first:
 * <pre>
 * window.setOnCloseRequest(request -&gt; {
 *     if (dirty) {
 *         request.cancel();
 *         window.confirm("저장하지 않고 닫을까요?").thenAccept(ok -&gt; { if (ok) window.close(); });
 *     }
 * });
 * </pre>
 */
public final class CloseRequest {

    private final Window window;
    private boolean cancelled;

    CloseRequest(Window window) {
        this.window = window;
    }

    public Window getWindow() {
        return window;
    }

    /** Keeps the window open. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
