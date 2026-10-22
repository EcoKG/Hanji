package io.hanji.spi;

import io.hanji.WindowState;

import java.util.List;
import java.util.Map;

/**
 * A native window opened by a {@link WindowHost}. The native-control methods are optional: hosts that cannot
 * control their windows (a browser tab, for example) keep the defaults and report
 * {@link #supportsNativeControls()} {@code false}; Hanji then ignores those calls with a warning.
 */
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

    /** Whether the methods below are implemented. */
    default boolean supportsNativeControls() {
        return false;
    }

    /** Moves/resizes the window; {@code null} keeps the current value. */
    default void setBounds(Integer x, Integer y, Integer width, Integer height) {
    }

    default void center() {
    }

    default void setState(WindowState state) {
    }

    /** Shows, restores and focuses the window. */
    default void toFront() {
    }

    default void setResizable(boolean resizable) {
    }

    default void setAlwaysOnTop(boolean alwaysOnTop) {
    }

    default void setMinimumSize(int width, int height) {
    }

    /** When enabled, a user's attempt to close the window is reported via {@link HostCallbacks#closeRequested()} instead. */
    default void setCloseGuard(boolean enabled) {
    }

    /**
     * Sets the window's menu bar (on macOS: the application menu while the window is focused).
     * Each menu is a map with {@code label} and {@code items}; see {@link io.hanji.menu.MenuBar#toTemplate(java.util.Map)}.
     */
    default void setMenu(List<Map<String, Object>> menus) {
    }
}
