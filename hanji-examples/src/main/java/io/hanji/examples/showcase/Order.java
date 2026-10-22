package io.hanji.examples.showcase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** An order shown in the showcase (status and notes change while the app runs). */
final class Order {

    enum Status {
        PAID("결제 완료", "paid"), SHIPPING("배송 중", "shipping"), DELIVERED("배송 완료", "delivered"),
        ON_HOLD("보류", "hold"), REFUNDED("환불", "refunded");

        final String label;
        final String css;

        Status(String label, String css) {
            this.label = label;
            this.css = css;
        }
    }

    record Product(String name, long price, String color) {
    }

    record Item(Product product, int quantity) {
        long amount() {
            return product.price() * quantity;
        }
    }

    record Note(LocalDateTime at, String text) {
    }

    final String id;
    final Customer customer;
    final String channel;
    final LocalDateTime createdAt;
    final List<Item> items;
    final List<Note> notes = new ArrayList<>();
    Status status;
    /** Created in this session (shown with a NEW badge). */
    boolean fresh;

    Order(String id, Customer customer, String channel, LocalDateTime createdAt, List<Item> items, Status status) {
        this.id = id;
        this.customer = customer;
        this.channel = channel;
        this.createdAt = createdAt;
        this.items = items;
        this.status = status;
    }

    long total() {
        return items.stream().mapToLong(Item::amount).sum();
    }

    int quantity() {
        return items.stream().mapToInt(Item::quantity).sum();
    }

    String summary() {
        String first = items.get(0).product().name();
        return items.size() == 1 ? first : first + " 외 " + (items.size() - 1) + "건";
    }

    boolean canShip() {
        return status == Status.PAID || status == Status.ON_HOLD;
    }

    boolean matches(String query) {
        return query.isEmpty() || (id + " " + customer.name + " " + customer.email + " " + channel + " "
                + String.join(" ", items.stream().map(i -> i.product().name()).toList())).toLowerCase().contains(query);
    }
}
