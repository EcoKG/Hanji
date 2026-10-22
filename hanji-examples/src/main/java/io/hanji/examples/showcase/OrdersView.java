package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Window;
import io.hanji.ui.TableView;

import java.util.List;

/** Orders page: sortable table with status tabs (counts follow the search), export and new order. */
final class OrdersView {

    private static final String[] TABS = {"ALL", "PAID", "SHIPPING", "DELIVERED", "ON_HOLD"};

    private final MainController main;
    private final Window window;
    private final Store store;
    private final TableView<Order> table;
    private String status = "ALL";

    OrdersView(MainController main) {
        this.main = main;
        this.window = main.window;
        this.store = main.store;
        table = new TableView<>(window.byId("allOrders"));
        table.addColumn("주문번호", o -> o.id).setCellFactory(o -> Ui.span("oid", o.id).toggleClass("fresh", o.fresh));
        table.addColumn("고객", o -> o.customer.name).setCellFactory(Ui::customerCell);
        table.addColumn("상품", Order::summary);
        table.addColumn("수량", Order::quantity).setAlign("right");
        table.addColumn("채널", o -> o.channel).setCellFactory(o -> Ui.span("channel-tag", o.channel));
        table.addColumn("금액", Order::total).setAlign("right").setCellFactory(o -> Element.create("strong").setText(Ui.won(o.total())));
        table.addColumn("상태", o -> o.status.ordinal()).setCellFactory(o -> Ui.pill(o.status));
        table.addColumn("주문 시각", o -> o.createdAt).setCellFactory(o -> Ui.span("dim", Ui.when(o.createdAt)));
        table.setPlaceholder("조건에 맞는 주문이 없습니다");
        table.setOnRowActivated(main::openOrder);

        for (String tab : TABS) {
            window.byId("tab-" + tab).onClick(e -> setStatus(tab));
        }
        window.byId("exportBtn").onClick(e -> main.exportCsv());
        window.byId("newOrderBtn").onClick(e -> main.newOrder(null));
    }

    Order selected() {
        return table.getSelectedItem();
    }

    void select(Order order) {
        table.select(order);
    }

    List<Order> visible() {
        return List.copyOf(table.getItems());
    }

    void showAll() {
        setStatus("ALL");
    }

    void setStatus(String newStatus) {
        status = newStatus;
        for (String tab : TABS) {
            window.byId("tab-" + tab).toggleClass("active", tab.equals(newStatus));
        }
        refresh();
    }

    void refresh() {
        Order selected = table.getSelectedItem();
        List<Order> matching = store.orders.stream().filter(o -> o.matches(main.query())).toList();
        for (String tab : TABS) {
            long n = matching.stream().filter(o -> "ALL".equals(tab) || o.status.name().equals(tab)).count();
            window.byId("cnt-" + tab).setText(String.valueOf(n));
        }
        List<Order> rows = matching.stream().filter(o -> "ALL".equals(status) || o.status.name().equals(status)).toList();
        table.getItems().setAll(rows);
        table.select(selected);
    }
}
