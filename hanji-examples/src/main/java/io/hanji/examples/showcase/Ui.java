package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatting and small element factories shared by the showcase screens. */
final class Ui {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm", Locale.KOREAN);
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String[] COLORS = {"#6366f1", "#0ea5e9", "#10b981", "#f59e0b", "#ec4899", "#8b5cf6", "#14b8a6", "#ef4444"};

    private Ui() {
    }

    static String won(long value) {
        return String.format("₩%,d", value);
    }

    static String when(LocalDateTime t) {
        if (t.toLocalDate().equals(DemoData.TODAY)) {
            return "오늘 " + t.format(TIME);
        }
        return (t.toLocalDate().equals(DemoData.TODAY.minusDays(1)) ? "어제 " : t.getMonthValue() + "/" + t.getDayOfMonth() + " ")
                + t.format(TIME);
    }

    static String avatarColor(String key) {
        return COLORS[Math.floorMod(key.hashCode(), COLORS.length)];
    }

    static Element avatar(String name, String... classes) {
        return Element.create("span").addClass("ava").addClass(classes)
                .setStyle("--c", avatarColor(name)).setText(name.substring(0, 1));
    }

    static Element pill(Order.Status status) {
        return Element.create("span").addClass("pill", status.css).setText(status.label);
    }

    static Element grade(Customer.Grade grade) {
        return Element.create("span").addClass("grade", grade.css).setText(grade.label);
    }

    static Element customerCell(Order o) {
        return Element.create("div").addClass("cust").append(avatar(o.customer.name), Element.create("span").setText(o.customer.name));
    }

    static Element span(String cssClass, String text) {
        return Element.create("span").addClass(cssClass).setText(text);
    }

    /** Applies the appearance settings to a page (tokens are switched by attributes on body, see showcase.css). */
    static void applyAppearance(Window window, Store store) {
        Element body = window.body();
        if ("system".equals(store.theme)) {
            body.removeAttribute("data-theme");
        } else {
            body.setAttribute("data-theme", store.theme);
        }
        body.setAttribute("data-accent", store.accent);
    }

    /** Product rows with a coloured initial, quantity and amount. */
    static void renderItems(Element list, Order o) {
        list.clear();
        for (Order.Item item : o.items) {
            Order.Product p = item.product();
            list.append(Element.create("li").append(
                    Element.create("span").addClass("thumb").setStyle("--c", p.color()).setText(p.name().substring(0, 1)),
                    Element.create("div").append(
                            Element.create("div").addClass("name").setText(p.name()),
                            Element.create("div").addClass("qty").setText(won(p.price()) + " × " + item.quantity())),
                    span("price", won(item.amount()))));
        }
    }

    /** Order progress: received, paid, shipped, delivered. */
    static void renderTimeline(Element list, Order o) {
        int stage = switch (o.status) {
            case PAID, ON_HOLD, REFUNDED -> 2;
            case SHIPPING -> 3;
            case DELIVERED -> 4;
        };
        String[][] steps = {
                {"주문 접수", o.createdAt.format(TIME)},
                {"결제 완료", o.createdAt.plusMinutes(2).format(TIME)},
                {o.status == Order.Status.REFUNDED ? "환불 완료" : "배송 시작",
                        o.status == Order.Status.ON_HOLD ? "보류됨" : o.status == Order.Status.REFUNDED ? "처리됨"
                                : stage >= 3 ? o.createdAt.plusHours(3).format(TIME) : "대기"},
                {"배송 완료", stage >= 4 ? "10월 22일" : "예정"}};
        list.clear();
        for (int i = 0; i < steps.length; i++) {
            boolean done = i < stage || (i == 2 && o.status == Order.Status.REFUNDED);
            list.append(Element.create("li").toggleClass("done", done).append(
                    Element.create("span").setText(steps[i][0]),
                    Element.create("time").setText(steps[i][1])));
        }
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
