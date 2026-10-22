package io.hanji.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A menu entry: a command, a checkbox, a separator, a platform role or a submenu.
 * <pre>
 * new MenuItem("저장", "CmdOrCtrl+S", this::save)
 * MenuItem.checkbox("자동 저장", true, on -&gt; autoSave = on)
 * MenuItem.role(MenuRole.QUIT)
 * </pre>
 * Accelerators use Electron syntax: {@code CmdOrCtrl+Shift+N}, {@code Alt+F4}, {@code F5}.
 * Changing a property after the menu is shown updates the native menu.
 */
public class MenuItem extends MenuNode {

    private static final AtomicLong IDS = new AtomicLong();

    private final String id = "mi" + IDS.incrementAndGet();
    private final String type;
    private final MenuRole role;
    private final List<MenuItem> items;
    private String label;
    private String accelerator;
    private boolean enabled = true;
    private boolean checked;
    private Consumer<MenuItem> action;

    public MenuItem(String label) {
        this("normal", label, null, null);
    }

    public MenuItem(String label, Runnable action) {
        this(label);
        setOnAction(action);
    }

    public MenuItem(String label, String accelerator, Runnable action) {
        this(label, action);
        this.accelerator = accelerator;
    }

    private MenuItem(String type, String label, MenuRole role, List<MenuItem> items) {
        this.type = type;
        this.label = label;
        this.role = role;
        this.items = items;
        if (items != null) {
            items.forEach(this::adopt);
        }
    }

    public static MenuItem separator() {
        return new MenuItem("separator", null, null, null);
    }

    /** A platform item such as copy, paste or quit. */
    public static MenuItem role(MenuRole role) {
        return new MenuItem("normal", null, Objects.requireNonNull(role), null);
    }

    /** A platform item with a custom label. */
    public static MenuItem role(MenuRole role, String label) {
        return new MenuItem("normal", label, Objects.requireNonNull(role), null);
    }

    public static MenuItem checkbox(String label, boolean checked, Consumer<Boolean> onToggle) {
        MenuItem item = new MenuItem("checkbox", label, null, null);
        item.checked = checked;
        if (onToggle != null) {
            item.action = i -> onToggle.accept(i.isChecked());
        }
        return item;
    }

    public static MenuItem submenu(String label, MenuItem... items) {
        return new MenuItem("submenu", label, null, new ArrayList<>(List.of(items)));
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public MenuItem setLabel(String label) {
        this.label = label;
        changed();
        return this;
    }

    public String getAccelerator() {
        return accelerator;
    }

    public MenuItem setAccelerator(String accelerator) {
        this.accelerator = accelerator;
        changed();
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MenuItem setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            changed();
        }
        return this;
    }

    public boolean isChecked() {
        return checked;
    }

    public MenuItem setChecked(boolean checked) {
        if (this.checked != checked) {
            this.checked = checked;
            changed();
        }
        return this;
    }

    public MenuItem setOnAction(Runnable action) {
        this.action = action == null ? null : i -> action.run();
        return this;
    }

    public MenuItem setOnAction(Consumer<MenuItem> action) {
        this.action = action;
        return this;
    }

    /** Children of a submenu (empty for other items). */
    public List<MenuItem> getItems() {
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    /** Called by Hanji when the user chose this item. */
    public void fire(Boolean newChecked) {
        if (newChecked != null && "checkbox".equals(type)) {
            checked = newChecked;
        }
        if (action != null && enabled) {
            action.accept(this);
        }
    }

    Map<String, Object> toTemplate(Map<String, MenuItem> index) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if ("separator".equals(type)) {
            return m;
        }
        m.put("id", id);
        index.put(id, this);
        if (label != null) {
            m.put("label", label);
        }
        if (role != null) {
            m.put("role", role.id);
        }
        if (accelerator != null) {
            m.put("accelerator", accelerator);
        }
        if (!enabled) {
            m.put("enabled", false);
        }
        if ("checkbox".equals(type)) {
            m.put("checked", checked);
        }
        if (items != null) {
            m.put("items", templates(items, index));
        }
        return m;
    }

    static List<Map<String, Object>> templates(List<MenuItem> items, Map<String, MenuItem> index) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            out.add(item.toTemplate(index));
        }
        return out;
    }
}
