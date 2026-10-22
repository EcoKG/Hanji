package io.hanji.spi;

import java.util.List;
import java.util.Map;

/** A system tray icon created by a {@link WindowHost}. */
public interface HostTray {

    /** Replaces icon (file path, {@code null} for the default), tooltip and context menu items. */
    void update(String iconPath, String tooltip, List<Map<String, Object>> items);

    void remove();

    /** Callbacks of a tray icon. May be called from any thread. */
    interface Callbacks {
        void clicked();

        void menuAction(String id, Boolean checked);
    }
}
