package io.hanji.spi;

/**
 * Displays Hanji windows: e.g. a Chromium app window or a browser tab. Custom hosts
 * (an embedded webview, ...) can be plugged in through {@link WindowHostProvider}.
 * Every window is just a URL, so any engine that can load a URL and run modern JavaScript works.
 */
public interface WindowHost {

    String name();

    /** Opens a window for {@code spec.url()}. Called on the Hanji UI thread. */
    HostWindow open(HostWindowSpec spec, HostCallbacks callbacks) throws Exception;

    /** Releases all resources (called once when the application exits, possibly from a shutdown hook). */
    default void shutdown() {
    }
}
