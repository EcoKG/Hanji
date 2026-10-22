package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Window;
import io.hanji.WindowState;

/** Settings page: native window control (always on top, size, full screen), theme, accent colour, account. */
final class SettingsView {

    private static final String[] THEMES = {"system", "light", "dark"};
    private static final String[] ACCENTS = {"indigo", "blue", "green", "pink"};

    private final MainController main;
    private final Window window;
    private final Store store;
    private final Element alwaysOnTop;
    private final Element fullScreen;

    SettingsView(MainController main) {
        this.main = main;
        this.window = main.window;
        this.store = main.store;
        alwaysOnTop = window.byId("alwaysOnTop");
        fullScreen = window.byId("fullScreen");
        Element sizeNow = window.byId("sizeNow");

        alwaysOnTop.onChange(e -> main.setAlwaysOnTop(e.isChecked()));
        fullScreen.onChange(e -> window.setFullScreen(e.isChecked()));
        window.byId("size1280").onClick(e -> window.restore().setSize(1280, 800).center());
        window.byId("size1440").onClick(e -> window.restore().setSize(1440, 900).center());
        window.byId("sizeMax").onClick(e -> window.maximize());
        for (String theme : THEMES) {
            window.byId("theme-" + theme).onClick(e -> main.setTheme(theme));
        }
        for (String accent : ACCENTS) {
            window.byId("accent-" + accent).onClick(e -> main.setAccent(accent));
        }
        window.byId("logoutBtn").onClick(e -> main.logout());

        // window properties outlive the page, so these registrations are removed on logout
        main.registrations.add(window.boundsProperty().subscribe(b ->
                sizeNow.setText(b == null ? "-" : b.width() + " × " + b.height() + " (" + b.x() + ", " + b.y() + ")")));
        main.registrations.add(window.stateProperty().subscribe(s -> fullScreen.setChecked(s == WindowState.FULLSCREEN)));
    }

    void refresh() {
        Store.User user = store.user;
        window.byId("accountAvatar").setText(user.initials());
        window.byId("accountName").setText(user.name() + " (" + user.id() + ")");
        window.byId("accountRole").setText(user.role());
        alwaysOnTop.setChecked(main.alwaysOnTop);
        for (String theme : THEMES) {
            window.byId("theme-" + theme).toggleClass("active", theme.equals(store.theme));
        }
        for (String accent : ACCENTS) {
            window.byId("accent-" + accent).toggleClass("active", accent.equals(store.accent));
        }
        window.byId("runtimeInfo").setText("Java " + System.getProperty("java.version") + " · "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch") + " · "
                + (window.hasNativeControls() ? "Electron 네이티브 창" : "브라우저 앱 창"));
    }
}
