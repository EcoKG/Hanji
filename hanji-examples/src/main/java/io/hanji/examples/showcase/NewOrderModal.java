package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Window;
import io.hanji.ui.ComboBox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** "New order" modal: an HTML {@code <dialog>} driven from Java, with ComboBoxes, a live total and validation. */
final class NewOrderModal {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final MainController main;
    private final Element dialog;
    private final Element customer;
    private final Element email;
    private final Element quantity;
    private final Element memo;
    private final Element total;
    private final Element error;
    private final ComboBox<Order.Product> product;
    private final ComboBox<String> channel;

    NewOrderModal(MainController main) {
        this.main = main;
        Window window = main.window;
        dialog = window.byId("newOrderModal");
        customer = window.byId("noCustomer");
        email = window.byId("noEmail");
        quantity = window.byId("noQty");
        memo = window.byId("noMemo");
        total = window.byId("noTotal");
        error = window.byId("noError");

        product = new ComboBox<>(window.byId("noProduct"));
        product.getItems().setAll(DemoData.PRODUCTS);
        product.setConverter(p -> p.name() + "  ·  " + Ui.won(p.price()));
        product.setPlaceholder("상품을 선택하세요");
        product.valueProperty().addListener((obs, old, p) -> updateTotal());
        channel = new ComboBox<>(window.byId("noChannel"));
        channel.getItems().setAll(DemoData.CHANNELS);

        quantity.onInput(e -> updateTotal());
        window.byId("newOrderForm").on("submit", e -> submit());
        window.byId("newOrderClose").onClick(e -> close());
        window.byId("newOrderCancel").onClick(e -> close());
    }

    void open(Customer prefill) {
        customer.setValue(prefill == null ? "" : prefill.name);
        email.setValue(prefill == null ? "" : prefill.email);
        product.setValue(null);
        channel.setValue(DemoData.CHANNELS.get(0));
        quantity.setValue("1");
        memo.setValue("");
        for (Element field : new Element[]{customer, email, product.element(), quantity}) {
            field.removeClass("invalid");
        }
        error.setText("");
        updateTotal();
        dialog.invoke("showModal");
        (prefill == null ? customer : product.element()).focus();
    }

    void close() {
        dialog.invoke("close");
    }

    /** Fills the form like a user would (used by the screenshot tour). */
    void fill(String name, String mail, int productIndex, int qty, String salesChannel, String note) {
        customer.setValue(name);
        email.setValue(mail);
        product.setValue(DemoData.PRODUCTS.get(productIndex));
        quantity.setValue(String.valueOf(qty));
        channel.setValue(salesChannel);
        memo.setValue(note);
        updateTotal();
    }

    void submit() {
        String name = text(customer);
        String mail = text(email);
        Order.Product p = product.getValue();
        int qty = quantity();
        List<String> errors = new ArrayList<>();
        mark(customer, name.length() < 2, "고객 이름을 2자 이상 입력하세요", errors);
        mark(email, !EMAIL.matcher(mail).matches(), "이메일 형식이 올바르지 않습니다", errors);
        mark(product.element(), p == null, "상품을 선택하세요", errors);
        mark(quantity, qty < 1 || qty > 99, "수량은 1~99 사이로 입력하세요", errors);
        if (!errors.isEmpty()) {
            error.setText(String.join(" · ", errors));
            return;
        }
        Order order = main.store.createOrder(name, mail, p, qty, channel.getValue(), text(memo));
        close();
        main.orderCreated(order);
    }

    private void updateTotal() {
        Order.Product p = product.getValue();
        int qty = quantity();
        total.setText(p == null || qty < 1 ? "₩0" : Ui.won(p.price() * qty));
    }

    private int quantity() {
        try {
            return Integer.parseInt(text(quantity));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Element field) {
        return field.getValue() == null ? "" : field.getValue().trim();
    }

    private static void mark(Element field, boolean invalid, String message, List<String> errors) {
        field.toggleClass("invalid", invalid);
        if (invalid) {
            errors.add(message);
        }
    }
}
