package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Event;
import io.hanji.UiField;
import io.hanji.UiHandler;
import io.hanji.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller of {@code web/showcase/order.html}, shown in a separate native window per order. Changes made here go
 * through the {@link Store}, so the main window updates at the same time (and shows a toast).
 */
public class OrderWindowController {

    private static final Map<String, Window> OPEN = new LinkedHashMap<>();

    @UiField Window window;
    @UiField Element avatar;
    @UiField Element orderId;
    @UiField Element created;
    @UiField Element status;
    @UiField Element customer;
    @UiField Element email;
    @UiField Element channel;
    @UiField Element grade;
    @UiField Element items;
    @UiField Element total;
    @UiField Element timeline;
    @UiField Element notes;
    @UiField Element noteInput;
    @UiField Element refundBtn;
    @UiField Element holdBtn;
    @UiField Element shipBtn;

    private final Store store;
    private final Order order;
    private boolean confirming;

    private OrderWindowController(Store store, Order order) {
        this.store = store;
        this.order = order;
    }

    /** Opens the window of {@code order}, or brings the already open one to the front. */
    static Window open(Store store, Order order, Window owner) {
        Window existing = OPEN.get(order.id);
        if (existing != null && !existing.isClosed()) {
            return existing.toFront();
        }
        Window w = new Window()
                .setTitle("주문 " + order.id + " — Hanji Studio")
                .setSize(460, 760)
                .setMinimumSize(400, 560);
        if (owner.getBounds() != null) {
            int offset = 28 * OPEN.size();
            w.setPosition(owner.getX() + owner.getWidth() - 480 - offset, owner.getY() + 40 + offset);
        }
        w.load("showcase/order.html", new OrderWindowController(store, order)).show();
        OPEN.put(order.id, w);
        w.onClosed(() -> OPEN.remove(order.id, w));
        return w;
    }

    static Window find(String orderId) {
        return OPEN.get(orderId);
    }

    static void closeAll() {
        new ArrayList<>(OPEN.values()).forEach(Window::close);
    }

    void initialize() {
        render();
        var registration = store.onChange(change -> {
            if (change.order() == null || change.order() == order) {
                render();
            }
        });
        window.onClosed(registration::remove);
    }

    private void render() {
        Ui.applyAppearance(window, store);
        Customer c = order.customer;
        Customer.Grade g = store.grade(c);
        avatar.setText(c.initials()).setStyle("--c", Ui.avatarColor(c.name));
        orderId.setText(order.id);
        created.setText(order.createdAt.format(Ui.DATE_TIME) + " · " + order.quantity() + "개 상품");
        status.setAttribute("class", "pill " + order.status.css).setText(order.status.label);
        customer.setText(c.name);
        email.setText(c.email);
        channel.setText(order.channel);
        grade.setAttribute("class", "grade " + g.css).setText(g.label);
        Ui.renderItems(items, order);
        total.setText(Ui.won(order.total()));
        Ui.renderTimeline(timeline, order);
        notes.clear();
        if (order.notes.isEmpty()) {
            notes.append(Element.create("li").addClass("dim").setText("메모가 없습니다"));
        }
        for (Order.Note note : order.notes) {
            notes.append(Element.create("li").append(
                    Element.create("time").setText(Ui.when(note.at())), Element.create("span").setText(note.text())));
        }
        shipBtn.setEnabled(order.canShip());
        holdBtn.setEnabled(order.status == Order.Status.PAID);
        refundBtn.setEnabled(order.status != Order.Status.REFUNDED);
    }

    @UiHandler("shipBtn")
    void ship() {
        store.setStatus(order, Order.Status.SHIPPING, "주문 창");
    }

    @UiHandler("holdBtn")
    void hold() {
        store.setStatus(order, Order.Status.ON_HOLD, "주문 창");
    }

    @UiHandler("refundBtn")
    void refund() {
        confirming = true;
        window.confirm("환불", order.id + " 주문을 " + Ui.won(order.total()) + " 환불할까요?\n이 작업은 되돌릴 수 없습니다.")
                .thenAccept(ok -> {
                    confirming = false;
                    if (ok) {
                        store.setStatus(order, Order.Status.REFUNDED, "주문 창");
                    }
                });
    }

    @UiHandler(value = "noteInput", event = "keydown", keys = "Enter")
    void addNote(Event e) {
        if (!e.isMetaDown() && !e.isControlDown()) {
            return;
        }
        String text = noteInput.getValue() == null ? "" : noteInput.getValue().trim();
        if (!text.isEmpty()) {
            noteInput.setValue("");
            store.addNote(order, text);
        }
    }

    @UiHandler(value = "#document", event = "keydown", keys = "Escape")
    void escape() {
        if (!confirming) { // Escape in the confirm dialog only cancels the dialog
            window.close();
        }
    }
}
