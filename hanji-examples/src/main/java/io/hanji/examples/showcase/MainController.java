package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Event;
import io.hanji.Hanji;
import io.hanji.Registration;
import io.hanji.UiField;
import io.hanji.UiHandler;
import io.hanji.Window;
import io.hanji.menu.Menu;
import io.hanji.menu.MenuBar;
import io.hanji.menu.MenuItem;
import io.hanji.menu.MenuRole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Controller of {@code web/showcase/index.html}, the application shell: page navigation (sidebar, native menu,
 * shortcuts), search, appearance, logout and the native menu bar. Each page is handled by its own view class.
 */
public class MainController {

    private static final String[] PAGES = {"dashboard", "orders", "customers", "settings"};
    private static final Map<String, String[]> HEADINGS = Map.of(
            "dashboard", new String[]{"대시보드", "2026년 10월 22일 기준 · 실시간"},
            "orders", new String[]{"주문", "결제·배송 상태를 관리합니다 · 행을 더블클릭하면 새 창으로 열립니다"},
            "customers", new String[]{"고객", "등급과 구매 이력을 확인합니다"},
            "settings", new String[]{"설정", "창 · 모양 · 계정"});
    private static final Map<String, String> THEMES = Map.of("system", "시스템 설정", "light", "라이트", "dark", "다크");

    @UiField Window window;
    @UiField Element pageTitle;
    @UiField Element subtitle;
    @UiField Element range;
    @UiField Element searchBox;
    @UiField Element search;
    @UiField Element pendingCount;
    @UiField Element meAvatar;

    final Store store;
    /** Registrations that outlive the page (store, window properties); removed on logout. */
    final List<Registration> registrations = new ArrayList<>();
    DashboardView dashboard;
    OrdersView orders;
    CustomersView customers;
    SettingsView settings;
    NewOrderModal newOrderModal;
    CustomerModal customerModal;
    boolean alwaysOnTop;
    private String page = "dashboard";
    private String query = "";
    private MenuItem alwaysOnTopItem;
    private final Map<String, MenuItem> themeItems = new LinkedHashMap<>();

    MainController(Store store) {
        this.store = store;
    }

    void initialize() {
        Ui.applyAppearance(window, store);
        meAvatar.setText(store.user.initials()).setTooltip(store.user.name() + " · " + store.user.role());
        dashboard = new DashboardView(this);
        orders = new OrdersView(this);
        customers = new CustomersView(this);
        settings = new SettingsView(this);
        newOrderModal = new NewOrderModal(this);
        customerModal = new CustomerModal(this);
        window.setMenuBar(menuBar());
        registrations.add(store.onChange(this::storeChanged));
        refreshAll();
        navigate("dashboard");
        toast(store.user.name() + "님, 환영합니다", "info", "배송 대기 주문 " + store.pendingCount() + "건이 있습니다");
    }

    // ------------------------------------------------------------------ navigation

    @UiHandler({"nav-dashboard", "nav-orders", "nav-customers", "nav-settings"})
    void onNavigate(Event e) {
        navigate(e.getData("page"));
    }

    @UiHandler("viewAllOrders")
    void viewAllOrders() {
        navigate("orders");
    }

    @UiHandler("profile")
    void profile() {
        navigate("settings");
    }

    void navigate(String target) {
        page = target;
        for (String p : PAGES) {
            window.byId("nav-" + p).toggleClass("active", p.equals(target));
            window.byId("view-" + p).toggleClass("active", p.equals(target));
        }
        String[] heading = HEADINGS.get(target);
        pageTitle.setText(heading[0]);
        subtitle.setText(heading[1]);
        range.setVisible("dashboard".equals(target));
        searchBox.setVisible(!"settings".equals(target));
        window.setTitle("Hanji Studio — " + heading[0]);
        if ("dashboard".equals(target)) {
            window.call("showcase.redraw"); // the canvas has a size again
        } else if ("settings".equals(target)) {
            settings.refresh();
        }
    }

    String page() {
        return page;
    }

    String query() {
        return query;
    }

    @UiHandler(value = "search", event = "input", debounce = 150)
    void search(Event e) {
        query = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT);
        dashboard.refresh();
        orders.refresh();
        customers.refresh();
    }

    private void focusSearch() {
        if ("settings".equals(page)) {
            navigate("orders");
        }
        search.focus().invoke("select");
    }

    @UiHandler("notify")
    void notifications() {
        long paid = store.orders.stream().filter(o -> o.status == Order.Status.PAID).count();
        long hold = store.orders.stream().filter(o -> o.status == Order.Status.ON_HOLD).count();
        window.alert("알림", "배송 대기 " + paid + "건, 보류 " + hold + "건이 있습니다.\n보류 주문은 주문 › 보류 탭에서 확인하세요.");
    }

    // ------------------------------------------------------------------ actions (views, menus, tour)

    void newOrder(Customer prefill) {
        newOrderModal.open(prefill);
    }

    void showCustomer(Customer customer) {
        customerModal.open(customer);
    }

    void openOrder(Order order) {
        if (order != null) {
            OrderWindowController.open(store, order, window);
        }
    }

    void openSelectedOrder() {
        openOrder("orders".equals(page) ? orders.selected() : dashboard.selected());
    }

    void orderCreated(Order order) {
        navigate("orders");
        orders.showAll();
        orders.select(order);
        dashboard.select(order);
    }

    void toast(String text, String kind, String detail) {
        window.call("showcase.toast", text, kind, detail);
    }

    void exportCsv() {
        List<Order> rows = orders.visible();
        Hanji.async(() -> writeCsv(rows)).whenComplete((file, error) -> {
            if (error != null) {
                toast("내보내기 실패", "warn", error.getMessage());
            } else {
                toast("CSV로 내보냈습니다", "success", rows.size() + "건 · " + file);
            }
        });
    }

    private static Path writeCsv(List<Order> rows) throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        Path dir = Files.isDirectory(home.resolve("Downloads")) ? home.resolve("Downloads") : home;
        Path file = dir.resolve("hanji-orders-" + DemoData.TODAY + ".csv");
        StringBuilder csv = new StringBuilder("﻿주문번호,고객,이메일,상품,수량,채널,금액,상태,주문시각\n");
        for (Order o : rows) {
            csv.append(String.join(",", o.id, o.customer.name, o.customer.email, '"' + o.summary().replace("\"", "\"\"") + '"',
                    String.valueOf(o.quantity()), o.channel, String.valueOf(o.total()), o.status.label,
                    o.createdAt.toString())).append('\n');
        }
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file;
    }

    void logout() {
        window.confirm("로그아웃", store.user.name() + " 계정에서 로그아웃할까요?\n열려 있는 주문 창도 함께 닫힙니다.").thenAccept(ok -> {
            if (!ok) {
                return;
            }
            registrations.forEach(Registration::remove);
            registrations.clear();
            OrderWindowController.closeAll();
            store.user = null;
            window.load("showcase/login.html", new LoginController(store));
        });
    }

    // ------------------------------------------------------------------ appearance & window

    void setTheme(String theme) {
        store.theme = theme;
        themeItems.forEach((key, item) -> item.setChecked(key.equals(theme)));
        appearanceChanged();
    }

    void setAccent(String accent) {
        store.accent = accent;
        appearanceChanged();
    }

    private void appearanceChanged() {
        Ui.applyAppearance(window, store);
        settings.refresh();
        window.call("showcase.redraw");
        store.appearanceChanged(); // open order windows follow
    }

    void setAlwaysOnTop(boolean on) {
        alwaysOnTop = on;
        window.setAlwaysOnTop(on);
        alwaysOnTopItem.setChecked(on);
        settings.refresh();
    }

    private void storeChanged(Store.Change change) {
        if (change.order() == null && change.message() == null) {
            return; // appearance only
        }
        refreshAll();
        if (change.message() != null) {
            Order o = change.order();
            toast(change.message(), change.kind(), o == null ? null : o.customer.name + " · " + Ui.won(o.total()));
        }
    }

    private void refreshAll() {
        pendingCount.setText(String.valueOf(store.pendingCount()));
        dashboard.refresh();
        orders.refresh();
        customers.refresh();
    }

    // ------------------------------------------------------------------ native menu

    private MenuBar menuBar() {
        boolean mac = Ui.isMac();
        MenuBar bar = new MenuBar();
        if (mac) {
            bar.add(new Menu("Hanji Studio",
                    MenuItem.role(MenuRole.ABOUT, "Hanji Studio 정보"),
                    MenuItem.separator(),
                    new MenuItem("설정…", "CmdOrCtrl+,", () -> navigate("settings")),
                    MenuItem.separator(),
                    MenuItem.role(MenuRole.HIDE, "Hanji Studio 가리기"),
                    MenuItem.role(MenuRole.HIDE_OTHERS, "기타 가리기"),
                    MenuItem.role(MenuRole.UNHIDE, "모두 보기"),
                    MenuItem.separator(),
                    MenuItem.role(MenuRole.QUIT, "Hanji Studio 종료")));
        }
        Menu file = new Menu("파일",
                new MenuItem("새 주문…", "CmdOrCtrl+N", () -> newOrder(null)),
                new MenuItem("선택한 주문을 새 창으로 열기", "CmdOrCtrl+O", this::openSelectedOrder),
                new MenuItem("CSV로 내보내기", "CmdOrCtrl+E", this::exportCsv),
                MenuItem.separator(),
                new MenuItem("로그아웃…", "CmdOrCtrl+Shift+L", this::logout));
        if (!mac) {
            file.add(MenuItem.separator(), MenuItem.role(MenuRole.QUIT, "종료"));
        }
        bar.add(file, Menu.standard(MenuRole.EDIT_MENU, "편집"));
        bar.add(new Menu("이동",
                new MenuItem("대시보드", "CmdOrCtrl+1", () -> navigate("dashboard")),
                new MenuItem("주문", "CmdOrCtrl+2", () -> navigate("orders")),
                new MenuItem("고객", "CmdOrCtrl+3", () -> navigate("customers")),
                new MenuItem("설정", "CmdOrCtrl+4", () -> navigate("settings")),
                MenuItem.separator(),
                new MenuItem("검색", "CmdOrCtrl+K", this::focusSearch),
                new MenuItem("알림", this::notifications)));

        alwaysOnTopItem = MenuItem.checkbox("항상 위에 표시", alwaysOnTop, this::setAlwaysOnTop);
        List<MenuItem> themes = new ArrayList<>();
        for (String key : new String[]{"system", "light", "dark"}) {
            MenuItem item = MenuItem.checkbox(THEMES.get(key), key.equals(store.theme), on -> setTheme(key));
            themeItems.put(key, item);
            themes.add(item);
        }
        bar.add(new Menu("보기",
                alwaysOnTopItem,
                MenuItem.role(MenuRole.TOGGLE_FULL_SCREEN, "전체 화면"),
                MenuItem.separator(),
                MenuItem.submenu("테마", themes.toArray(new MenuItem[0])),
                MenuItem.separator(),
                MenuItem.role(MenuRole.RESET_ZOOM, "실제 크기"),
                MenuItem.role(MenuRole.ZOOM_IN, "확대"),
                MenuItem.role(MenuRole.ZOOM_OUT, "축소"),
                MenuItem.separator(),
                MenuItem.role(MenuRole.TOGGLE_DEV_TOOLS, "개발자 도구")));
        bar.add(Menu.standard(MenuRole.WINDOW_MENU, "윈도우"));
        return bar;
    }
}
