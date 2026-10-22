package io.hanji.spi;

import io.hanji.WindowState;

/** Notifications from a host to Hanji. May be called from any thread. */
public interface HostCallbacks {

    /** The native window was closed. */
    void closed();

    /** The user tried to close a window whose close guard is enabled. */
    default void closeRequested() {
    }

    /** Position, size, state or focus of the window changed. */
    default void geometryChanged(int x, int y, int width, int height, WindowState state, boolean focused) {
    }

    /** A menu item with {@code id} was chosen; {@code checked} is its new state for checkbox items. */
    default void menuAction(String id, Boolean checked) {
    }
}
