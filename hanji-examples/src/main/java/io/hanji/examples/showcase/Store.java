package io.hanji.examples.showcase;

import io.hanji.Registration;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * State shared by all windows of the showcase: data, the signed-in user and the appearance settings.
 * Every mutation is published as a {@link Change}, so the main window and open order windows stay in sync.
 * Accessed on the UI thread only.
 */
final class Store {

    /** What changed; {@code message} is shown as a toast in the main window (may be {@code null}). */
    record Change(String message, String kind, Order order) {
    }

    record User(String id, String name, String role) {
        String initials() {
            return name.substring(0, 1);
        }
    }

    final List<Customer> customers = DemoData.customers();
    final List<Order> orders = DemoData.orders(customers);
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private int nextNumber = 24872;

    User user;
    String rememberedId;
    String theme = "system";
    String accent = "indigo";

    Registration onChange(Consumer<Change> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void publish(String message, String kind, Order order) {
        Change change = new Change(message, kind, order);
        for (Consumer<Change> l : listeners) {
            l.accept(change);
        }
    }

    /** Demo authentication (called on a worker thread). */
    static User authenticate(String id, String password) {
        if ("admin".equals(id) && "hanji".equals(password)) {
            return new User("admin", "김한지", "스토어 관리자");
        }
        return null;
    }

    Order createOrder(String name, String email, Order.Product product, int quantity, String channel, String memo) {
        Customer customer = customers.stream().filter(c -> c.name.equals(name)).findFirst().orElseGet(() -> {
            Customer c = new Customer(name, email, DemoData.TODAY, 0, 0);
            customers.add(0, c);
            return c;
        });
        // the demo clock is frozen at TODAY: a new order is a few minutes newer than the latest one
        LocalDateTime now = orders.stream().map(o -> o.createdAt).max(LocalDateTime::compareTo)
                .orElse(DemoData.TODAY.atTime(12, 0)).plusMinutes(6);
        Order order = new Order(String.format("HJ-%05d", nextNumber++), customer, channel, now,
                new ArrayList<>(List.of(new Order.Item(product, quantity))), Order.Status.PAID);
        order.fresh = true;
        if (memo != null && !memo.isBlank()) {
            order.notes.add(new Order.Note(now, memo.trim()));
        }
        orders.add(0, order);
        publish("새 주문 " + order.id + " 등록 완료", "success", order);
        return order;
    }

    void setStatus(Order order, Order.Status status, String source) {
        order.status = status;
        String verb = switch (status) {
            case SHIPPING -> "배송을 시작했습니다";
            case ON_HOLD -> "보류했습니다";
            case REFUNDED -> "환불 처리했습니다";
            case DELIVERED -> "배송 완료로 변경했습니다";
            case PAID -> "결제 완료로 되돌렸습니다";
        };
        publish(order.id + " 주문을 " + verb + (source == null ? "" : " · " + source),
                status == Order.Status.REFUNDED || status == Order.Status.ON_HOLD ? "warn" : "success", order);
    }

    void addNote(Order order, String text) {
        order.notes.add(new Order.Note(DemoData.TODAY.atTime(LocalDateTime.now().toLocalTime().withNano(0)), text));
        publish(null, null, order);
    }

    void appearanceChanged() {
        publish(null, null, null);
    }

    Customer customer(String name) {
        return customers.stream().filter(c -> c.name.equals(name)).findFirst().orElse(null);
    }

    List<Order> ordersOf(Customer c) {
        return orders.stream().filter(o -> o.customer == c).sorted(Comparator.comparing((Order o) -> o.createdAt).reversed()).toList();
    }

    int orderCount(Customer c) {
        return c.pastOrders + (int) orders.stream().filter(o -> o.customer == c).count();
    }

    long spent(Customer c) {
        return c.pastSpent + orders.stream().filter(o -> o.customer == c && o.status != Order.Status.REFUNDED)
                .mapToLong(Order::total).sum();
    }

    Customer.Grade grade(Customer c) {
        if (c.since.isAfter(DemoData.TODAY.minusDays(60))) {
            return Customer.Grade.NEW;
        }
        return spent(c) >= 700_000 ? Customer.Grade.VIP : Customer.Grade.GOLD;
    }

    long pendingCount() {
        return orders.stream().filter(Order::canShip).count();
    }
}
