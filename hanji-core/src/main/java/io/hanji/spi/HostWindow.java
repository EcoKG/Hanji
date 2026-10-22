package io.hanji.spi;

/** A native window opened by a {@link WindowHost}. */
public interface HostWindow {

    /** Closes the native window. Called on the Hanji UI thread; must not block for long. */
    void close();

    /**
     * Whether a lost page connection means the window was closed. True for browser-based hosts that
     * cannot report window closing directly; false for hosts that call {@link HostCallbacks#closed()}.
     */
    default boolean closesWithPage() {
        return true;
    }
}
