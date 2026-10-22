package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.EventOptions;
import io.hanji.Window;

import java.util.List;

/** Customer profile modal with statistics and recent orders (a click on an order opens its window). */
final class CustomerModal {

    private final MainController main;
    private final Window window;
    private final Element dialog;
    private final Element orderList;
    private Customer current;

    CustomerModal(MainController main) {
        this.main = main;
        this.window = main.window;
        dialog = window.byId("customerModal");
        orderList = window.byId("cmOrderList");
        window.byId("customerClose").onClick(e -> close());
        window.byId("customerNewOrder").onClick(e -> {
            close();
            main.newOrder(current);
        });
        orderList.on("click", EventOptions.of().delegate("li[data-id]"), e -> main.store.orders.stream()
                .filter(o -> o.id.equals(e.getData("id"))).findFirst().ifPresent(main::openOrder));
    }

    void open(Customer c) {
        current = c;
        Store store = main.store;
        Customer.Grade grade = store.grade(c);
        window.byId("cmAvatar").setText(c.initials()).setStyle("--c", Ui.avatarColor(c.name));
        window.byId("cmName").setText(c.name);
        window.byId("cmEmail").setText(c.email);
        window.byId("cmGrade").setAttribute("class", "grade " + grade.css).setText(grade.label);
        window.byId("cmOrders").setText(store.orderCount(c) + "건");
        window.byId("cmSpent").setText(Ui.won(store.spent(c)));
        window.byId("cmSince").setText(c.since.format(Ui.DATE));
        List<Order> orders = store.ordersOf(c);
        orderList.clear();
        if (orders.isEmpty()) {
            orderList.append(Element.create("li").addClass("dim").setText("이번 기간 주문이 없습니다"));
        }
        for (Order o : orders) {
            orderList.append(Element.create("li").setAttribute("data-id", o.id).setStyle("cursor", "pointer").append(
                    Ui.span("oid", o.id), Ui.span("dim", o.summary()), Ui.pill(o.status), Ui.span("amount", Ui.won(o.total()))));
        }
        dialog.invoke("showModal");
    }

    void close() {
        dialog.invoke("close");
    }
}
