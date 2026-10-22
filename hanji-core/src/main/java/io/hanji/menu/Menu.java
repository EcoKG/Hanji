package io.hanji.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A top-level menu of a {@link MenuBar} ("파일", "편집" ...), or the context menu of a {@link io.hanji.Tray}. */
public class Menu extends MenuNode {

    private final List<MenuItem> items = new ArrayList<>();
    private final MenuRole role;
    private String label;
    private Runnable listener;

    public Menu(String label, MenuItem... items) {
        this.label = label;
        this.role = null;
        add(items);
    }

    private Menu(MenuRole role, String label) {
        this.role = Objects.requireNonNull(role);
        this.label = label;
    }

    /** A complete platform menu, e.g. {@code Menu.standard(MenuRole.EDIT_MENU)}. */
    public static Menu standard(MenuRole role) {
        return new Menu(role, null);
    }

    /** A complete platform menu with a custom title, e.g. {@code Menu.standard(MenuRole.EDIT_MENU, "편집")}. */
    public static Menu standard(MenuRole role, String label) {
        return new Menu(role, label);
    }

    public Menu add(MenuItem... newItems) {
        for (MenuItem item : newItems) {
            adopt(item);
            items.add(item);
        }
        changed();
        return this;
    }

    public Menu remove(MenuItem item) {
        if (items.remove(item)) {
            item.parent = null;
            changed();
        }
        return this;
    }

    public List<MenuItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public String getLabel() {
        return label;
    }

    public Menu setLabel(String label) {
        this.label = label;
        changed();
        return this;
    }

    /** Called when anything in this menu changes (set by the tray showing it). */
    public void setChangeListener(Runnable listener) {
        this.listener = listener;
    }

    @Override
    void changed() {
        if (listener != null) {
            listener.run();
        }
        super.changed();
    }

    Map<String, Object> toTemplate(Map<String, MenuItem> index) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (label != null) {
            m.put("label", label);
        }
        if (role != null) {
            m.put("role", role.id);
        } else {
            m.put("items", MenuItem.templates(items, index));
        }
        return m;
    }

    /** Item maps of this menu (for tray context menus). */
    public List<Map<String, Object>> itemTemplates(Map<String, MenuItem> index) {
        return MenuItem.templates(items, index);
    }
}
