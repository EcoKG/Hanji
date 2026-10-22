package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Window;
import io.hanji.ui.TableView;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Dashboard page: KPI cards and sparklines (built in Java), charts (data published to showcase.js), recent orders. */
final class DashboardView {

    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final long MONTHLY_GOAL = 150_000_000L;

    private final MainController main;
    private final Window window;
    private final Store store;
    private final TableView<Order> table;
    private final Element kpis;
    private final Element channelList;
    private final Element donutTotal;
    private final Element orderCount;
    private final Element shipBtn;
    private final Element holdBtn;
    private int rangeDays = 30;

    DashboardView(MainController main) {
        this.main = main;
        this.window = main.window;
        this.store = main.store;
        kpis = window.byId("kpis");
        channelList = window.byId("channelList");
        donutTotal = window.byId("donutTotal");
        orderCount = window.byId("orderCount");
        shipBtn = window.byId("shipBtn");
        holdBtn = window.byId("holdBtn");

        table = new TableView<>(window.byId("orders"));
        table.addColumn("주문번호", o -> o.id).setCellFactory(o -> Ui.span("oid", o.id).toggleClass("fresh", o.fresh));
        table.addColumn("고객", o -> o.customer.name).setCellFactory(Ui::customerCell);
        table.addColumn("상품", Order::summary).setCellFactory(o -> Ui.span("dim", o.summary()));
        table.addColumn("금액", Order::total).setAlign("right").setCellFactory(o -> Element.create("strong").setText(Ui.won(o.total())));
        table.addColumn("상태", o -> o.status.ordinal()).setCellFactory(o -> Ui.pill(o.status));
        table.addColumn("주문 시각", o -> o.createdAt).setCellFactory(o -> Ui.span("dim", Ui.when(o.createdAt)));
        table.setPlaceholder("조건에 맞는 주문이 없습니다");
        table.selectedItemProperty().addListener((obs, old, order) -> showDetail(order));
        table.setOnRowActivated(main::openOrder);

        for (int days : new int[]{7, 30, 90}) {
            window.byId("range" + days).onClick(e -> setRange(days));
        }
        shipBtn.onClick(e -> ship());
        holdBtn.onClick(e -> {
            Order o = table.getSelectedItem();
            if (o != null) {
                store.setStatus(o, Order.Status.ON_HOLD, null);
            }
        });
        window.byId("popOut").onClick(e -> main.openOrder(table.getSelectedItem()));
        renderPeriod();
    }

    Order selected() {
        return table.getSelectedItem();
    }

    void select(Order order) {
        table.select(order);
    }

    void refresh() {
        Order selected = table.getSelectedItem();
        List<Order> recent = store.orders.stream().filter(o -> o.matches(main.query())).limit(9).toList();
        table.getItems().setAll(recent);
        if (selected != null && recent.contains(selected)) {
            table.select(selected);
        } else {
            table.select(0);
        }
        long today = store.orders.stream().filter(o -> o.createdAt.toLocalDate().equals(DemoData.TODAY)).count();
        orderCount.setText("오늘 " + today + "건 · 대기 " + store.pendingCount() + "건");
        showDetail(table.getSelectedItem());
    }

    private void setRange(int days) {
        rangeDays = days;
        for (int d : new int[]{7, 30, 90}) {
            window.byId("range" + d).toggleClass("active", d == days);
        }
        renderPeriod();
    }

    private void ship() {
        Order order = table.getSelectedItem();
        if (order == null) {
            return;
        }
        window.confirm("배송 시작", order.id + " 주문(" + Ui.won(order.total()) + ")의 배송을 시작할까요?").thenAccept(ok -> {
            if (ok) {
                store.setStatus(order, Order.Status.SHIPPING, null);
            }
        });
    }

    // ------------------------------------------------------------------ KPI, charts, goal

    @SuppressWarnings("unchecked")
    private void renderPeriod() {
        Map<String, Object> revenue = DemoData.revenue(rangeDays);
        List<Integer> current = (List<Integer>) revenue.get("current");
        List<Integer> previous = (List<Integer>) revenue.get("previous");
        long sales = sum(current) * 10_000;
        long salesPrev = sum(previous) * 10_000;
        long count = Math.round(sales / 41_500.0);
        long countPrev = Math.round(salesPrev / 43_200.0);
        double refund = 1.8 + (rangeDays % 7) * 0.1;

        kpis.clear().append(
                kpi("매출", "#4f46e5", amount(sales), delta(sales, salesPrev), current, ICON_WON),
                kpi("주문 수", "#0284c7", new String[]{String.format("%,d", count), "건"}, delta(count, countPrev), current, ICON_BAG),
                kpi("평균 주문 금액", "#059669", new String[]{String.format("%,d", sales / Math.max(1, count)), "원"},
                        delta(sales * countPrev, salesPrev * count), previous, ICON_TAG),
                kpi("반품률", "#d97706", new String[]{String.format("%.1f", refund), "%"}, -0.3,
                        current.stream().map(v -> 1000 - v).collect(Collectors.toList()), ICON_RETURN));

        List<Map<String, Object>> channels = DemoData.channels(rangeDays);
        int total = channels.stream().mapToInt(c -> (Integer) c.get("value")).sum();
        donutTotal.setText(String.format("%,d", count));
        channelList.clear();
        for (Map<String, Object> c : channels) {
            int pct = Math.round((Integer) c.get("value") * 100f / total);
            channelList.append(Element.create("li").setStyle("--c", (String) c.get("color")).append(
                    Element.create("span").addClass("sw"),
                    Element.create("span").setText((String) c.get("name")),
                    Ui.span("pct", pct + "%"),
                    Element.create("div").addClass("meter").append(Element.create("div").setStyle("width", pct + "%"))));
        }

        long monthSales = sum((List<Integer>) DemoData.revenue(30).get("current")) * 10_000;
        int goal = (int) Math.min(100, monthSales * 100 / MONTHLY_GOAL);
        window.byId("goalPct").setText(goal + "%");
        window.byId("goalBar").setStyle("width", goal + "%");
        window.byId("goalText").setText(Ui.won(monthSales) + " / " + Ui.won(MONTHLY_GOAL));

        window.publish("revenue", revenue);
        window.publish("channels", channels);
    }

    private Element kpi(String label, String tone, String[] value, double delta, List<Integer> series, String icon) {
        Element card = Element.create("div").addClass("card", "kpi").setStyle("--tone", tone);
        card.append(
                Element.create("div").addClass("label").append(Element.create("span").addClass("icon").setHtml(icon),
                        Element.create("span").setText(label)),
                Element.create("div").addClass("value").append(
                        Element.create("span").setText(value[0]), Element.create("small").setText(value[1])),
                Element.create("div").append(
                        Ui.span("delta", (delta >= 0 ? "▲ " : "▼ ") + String.format("%.1f%%", Math.abs(delta)))
                                .addClass(delta >= 0 ? "up" : "down"),
                        Ui.span("vs", "지난 기간 대비")),
                sparkline(series));
        return card;
    }

    private static Element sparkline(List<Integer> series) {
        int n = series.size();
        double min = series.stream().mapToInt(Integer::intValue).min().orElse(0);
        double max = series.stream().mapToInt(Integer::intValue).max().orElse(1);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x = i * 100.0 / (n - 1);
            double y = 34 - 4 - (series.get(i) - min) / Math.max(1, max - min) * 26;
            line.append(i == 0 ? "M" : "L").append(String.format(Locale.ROOT, "%.1f %.1f ", x, y));
        }
        return Element.createNS(SVG, "svg").addClass("spark").setAttribute("viewBox", "0 0 100 34")
                .setAttribute("preserveAspectRatio", "none").append(
                        Element.createNS(SVG, "path").addClass("area").setAttribute("d", line + "L100 34 L0 34 Z"),
                        Element.createNS(SVG, "path").addClass("line").setAttribute("d", line.toString())
                                .setAttribute("vector-effect", "non-scaling-stroke"));
    }

    // ------------------------------------------------------------------ detail panel

    private void showDetail(Order o) {
        boolean has = o != null;
        shipBtn.setEnabled(has && o.canShip());
        holdBtn.setEnabled(has && o.status == Order.Status.PAID);
        window.byId("popOut").setEnabled(has);
        if (!has) {
            return;
        }
        window.byId("dId").setText(o.id);
        window.byId("dDate").setText(o.createdAt.format(Ui.DATE_TIME));
        window.byId("dStatus").setAttribute("class", "pill " + o.status.css).setText(o.status.label);
        window.byId("dAvatar").setText(o.customer.initials()).setStyle("--c", Ui.avatarColor(o.customer.name));
        window.byId("dCustomer").setText(o.customer.name);
        window.byId("dEmail").setText(o.customer.email);
        window.byId("dChannel").setText(o.channel);
        Ui.renderItems(window.byId("dItems"), o);
        window.byId("dTotal").setText(Ui.won(o.total()));
        Ui.renderTimeline(window.byId("dTimeline"), o);
    }

    private static long sum(List<Integer> values) {
        return values.stream().mapToLong(Integer::longValue).sum();
    }

    private static double delta(long now, long before) {
        return before == 0 ? 0 : (now - before) * 100.0 / before;
    }

    private static String[] amount(long won) {
        return won >= 100_000_000 ? new String[]{String.format("%.2f", won / 100_000_000.0), "억 원"}
                : new String[]{String.format("%,d", won / 10_000), "만 원"};
    }

    private static final String ICON_WON = "<svg viewBox=\"0 0 24 24\"><path d=\"M4 6l3 12 3-9h4l3 9 3-12M3 11h18\"/></svg>";
    private static final String ICON_BAG = "<svg viewBox=\"0 0 24 24\"><path d=\"M4 7h16l-1.5 11.5a2 2 0 0 1-2 1.5h-9a2 2 0 0 1-2-1.5z\"/><path d=\"M9 7V5a3 3 0 0 1 6 0v2\"/></svg>";
    private static final String ICON_TAG = "<svg viewBox=\"0 0 24 24\"><path d=\"M3 12V4h8l10 10-8 8z\"/><circle cx=\"7.5\" cy=\"8.5\" r=\"1.5\"/></svg>";
    private static final String ICON_RETURN = "<svg viewBox=\"0 0 24 24\"><path d=\"M9 14L4 9l5-5\"/><path d=\"M4 9h10.5a5.5 5.5 0 0 1 0 11H11\"/></svg>";
}
