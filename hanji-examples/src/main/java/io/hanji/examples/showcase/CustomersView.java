package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.EventOptions;
import io.hanji.Window;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Customers page: cards built in Java, grade filter, a click opens the customer modal (event delegation). */
final class CustomersView {

    private static final String[] GRADES = {"ALL", "VIP", "GOLD", "NEW"};

    private final MainController main;
    private final Window window;
    private final Store store;
    private final Element grid;
    private final Element count;
    private String grade = "ALL";

    CustomersView(MainController main) {
        this.main = main;
        this.window = main.window;
        this.store = main.store;
        grid = window.byId("customerGrid");
        count = window.byId("customerCount");
        for (String g : GRADES) {
            window.byId("grade-" + g).onClick(e -> setGrade(g));
        }
        // one listener for all cards, however often they are rebuilt
        grid.on("click", EventOptions.of().delegate(".ccard"), e -> {
            Customer c = store.customer(e.getData("name"));
            if (c != null) {
                main.showCustomer(c);
            }
        });
    }

    private void setGrade(String g) {
        grade = g;
        for (String other : GRADES) {
            window.byId("grade-" + other).toggleClass("active", other.equals(g));
        }
        refresh();
    }

    void refresh() {
        String q = main.query();
        List<Customer> list = store.customers.stream()
                .filter(c -> "ALL".equals(grade) || store.grade(c).name().equals(grade))
                .filter(c -> q.isEmpty() || (c.name + " " + c.email).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparingLong(store::spent).reversed())
                .toList();
        long max = list.stream().mapToLong(store::spent).max().orElse(1);
        grid.clear();
        if (list.isEmpty()) {
            grid.append(Element.create("div").addClass("empty").setText("조건에 맞는 고객이 없습니다"));
        }
        for (Customer c : list) {
            grid.append(card(c, max));
        }
        count.setText("고객 " + list.size() + "명 · 누적 결제 " + Ui.won(list.stream().mapToLong(store::spent).sum()));
    }

    private Element card(Customer c, long max) {
        long spent = store.spent(c);
        List<Order> orders = store.ordersOf(c);
        String last = orders.isEmpty() ? "-" : Ui.when(orders.get(0).createdAt);
        return Element.create("div").addClass("card", "ccard").setAttribute("data-name", c.name).append(
                Element.create("div").addClass("top").append(
                        Ui.avatar(c.name, "big"),
                        Element.create("div").append(Element.create("strong").setText(c.name), Element.create("span").setText(c.email)),
                        Ui.grade(store.grade(c))),
                Element.create("div").addClass("share").append(
                        Element.create("div").setStyle("width", Math.max(3, spent * 100 / Math.max(1, max)) + "%")),
                Element.create("div").addClass("nums").append(
                        num("주문", store.orderCount(c) + "건"), num("누적 결제", Ui.won(spent)), num("최근 주문", last)));
    }

    private static Element num(String label, String value) {
        return Element.create("div").append(Element.create("span").setText(label), Element.create("strong").setText(value));
    }
}
