package io.hanji.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The menu bar of a window (on macOS: the application menu bar while the window is focused; the app menu
 * and standard Edit/Window menus are added automatically so that Cmd+C, Cmd+V, Cmd+Q keep working).
 * <pre>
 * window.setMenuBar(new MenuBar(
 *     new Menu("파일",
 *         new MenuItem("새 파일", "CmdOrCtrl+N", this::newFile),
 *         MenuItem.separator(),
 *         MenuItem.role(MenuRole.QUIT)),
 *     Menu.standard(MenuRole.EDIT_MENU)));
 * </pre>
 */
public class MenuBar extends MenuNode {

    private final List<Menu> menus = new ArrayList<>();
    private Runnable listener;

    public MenuBar(Menu... menus) {
        add(menus);
    }

    public MenuBar add(Menu... newMenus) {
        for (Menu menu : newMenus) {
            adopt(menu);
            menus.add(menu);
        }
        changed();
        return this;
    }

    public MenuBar remove(Menu menu) {
        if (menus.remove(menu)) {
            menu.parent = null;
            changed();
        }
        return this;
    }

    public List<Menu> getMenus() {
        return Collections.unmodifiableList(menus);
    }

    /** Called when anything in the bar changes (set by the window showing it). */
    public void setChangeListener(Runnable listener) {
        this.listener = listener;
    }

    @Override
    void changed() {
        if (listener != null) {
            listener.run();
        }
    }

    /** The menu bar as maps for a window host; {@code index} receives every item by id. */
    public List<Map<String, Object>> toTemplate(Map<String, MenuItem> index) {
        List<Map<String, Object>> out = new ArrayList<>(menus.size());
        for (Menu menu : menus) {
            out.add(menu.toTemplate(index));
        }
        return out;
    }
}
