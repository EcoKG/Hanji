package io.hanji;

import io.hanji.menu.Menu;
import io.hanji.menu.MenuItem;
import io.hanji.spi.HostTray;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An icon in the system tray (Windows notification area, macOS menu bar, Linux status area).
 * <pre>
 * Tray tray = Tray.create()
 *         .setTooltip("Hanji 앱")
 *         .setMenu(new Menu(null,
 *                 new MenuItem("열기", window::toFront),
 *                 MenuItem.separator(),
 *                 new MenuItem("종료", Hanji::exit)))
 *         .setOnAction(window::toFront);
 * </pre>
 * Requires the electron host; with other hosts the tray is inert ({@link #isSupported()} is false).
 * On Linux, desktops without a status notifier (e.g. GNOME without the AppIndicator extension) hide tray icons.
 */
public final class Tray {

    private final HanjiRuntime runtime;
    private HostTray host;
    private String iconPath;
    private String tooltip = "";
    private Menu menu;
    private Runnable onAction;
    private Map<String, MenuItem> index = new HashMap<>();
    private boolean pending;
    private boolean removed;

    private Tray(HanjiRuntime runtime) {
        this.runtime = runtime;
    }

    /** Creates a tray icon with the default Hanji icon. Must be called on the UI thread. */
    public static Tray create() {
        HanjiRuntime rt = HanjiRuntime.get();
        rt.checkUiThread();
        Tray tray = new Tray(rt);
        try {
            tray.host = rt.host().createTray(tray.new Callbacks());
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the tray icon: " + e.getMessage(), e);
        }
        if (tray.host == null) {
            Log.warn("The '" + rt.host().name() + "' window host has no system tray; use the electron host");
        }
        tray.schedule();
        return tray;
    }

    public boolean isSupported() {
        return host != null;
    }

    /** Icon image file (PNG; on macOS a name ending in "Template.png" adapts to light and dark menu bars). */
    public Tray setIcon(Path file) {
        iconPath = file.toAbsolutePath().toString();
        schedule();
        return this;
    }

    /** Icon from the classpath, e.g. {@code "icons/tray.png"}. */
    public Tray setIcon(String classpathResource) {
        String name = classpathResource.startsWith("/") ? classpathResource.substring(1) : classpathResource;
        try (InputStream in = runtime.resourceLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("Tray icon not found on the classpath: " + name);
            }
            String fileName = Path.of(name).getFileName().toString();
            Path dir = Files.createTempDirectory("hanji-tray-");
            Path file = dir.resolve(fileName);
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return setIcon(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Tray setTooltip(String tooltip) {
        this.tooltip = tooltip == null ? "" : tooltip;
        schedule();
        return this;
    }

    /** Context menu (its label is not shown). Changes to the items are applied automatically. */
    public Tray setMenu(Menu menu) {
        if (this.menu != null) {
            this.menu.setChangeListener(null);
        }
        this.menu = menu;
        if (menu != null) {
            menu.setChangeListener(this::schedule);
        }
        schedule();
        return this;
    }

    /** Called when the icon is clicked. */
    public Tray setOnAction(Runnable action) {
        this.onAction = action;
        return this;
    }

    public void remove() {
        runtime.checkUiThread();
        if (removed) {
            return;
        }
        removed = true;
        if (menu != null) {
            menu.setChangeListener(null);
        }
        if (host != null) {
            host.remove();
        }
    }

    private void schedule() {
        if (host == null || removed || pending) {
            return;
        }
        pending = true;
        runtime.loop.defer(this::push);
    }

    private void push() {
        pending = false;
        if (removed) {
            return;
        }
        Map<String, MenuItem> newIndex = new HashMap<>();
        List<Map<String, Object>> items = menu == null ? List.of() : menu.itemTemplates(newIndex);
        index = newIndex;
        host.update(iconPath, tooltip, items);
    }

    private final class Callbacks implements HostTray.Callbacks {
        @Override
        public void clicked() {
            runtime.post(() -> {
                if (!removed && onAction != null) {
                    onAction.run();
                }
            });
        }

        @Override
        public void menuAction(String id, Boolean checked) {
            runtime.post(() -> {
                MenuItem item = index.get(id);
                if (!removed && item != null) {
                    item.fire(checked);
                }
            });
        }
    }
}
