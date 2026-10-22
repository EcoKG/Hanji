package io.hanji.ui;

import io.hanji.Element;
import io.hanji.Registration;
import io.hanji.property.ObservableValue;
import io.hanji.property.Property;
import io.hanji.property.SimpleProperty;

/** Displays text. Bind it with {@code label.textProperty().bind(...)} or {@link #bindText}. */
public class Label extends Component<Label> {

    private final SimpleProperty<String> text = new SimpleProperty<>();

    public Label() {
        this("");
    }

    public Label(String text) {
        this(Element.create("span"));
        element.addClass("hj-label");
        setText(text);
    }

    /** Wraps an existing element; its HTML text stays until {@link #setText} is called. */
    public Label(Element element) {
        super(element);
        text.addListener((obs, oldValue, newValue) -> element.setText(newValue));
    }

    /** A heading-styled label ({@code h2}). */
    public static Label heading(String text) {
        Label label = new Label(Element.create("h2").addClass("hj-heading"));
        label.setText(text);
        return label;
    }

    public Property<String> textProperty() {
        return text;
    }

    public String getText() {
        return text.get() == null ? "" : text.get();
    }

    public Label setText(String value) {
        text.set(value == null ? "" : value);
        return this;
    }

    /** Shows {@code String.valueOf(value)} of an observable value. */
    public Registration bindText(ObservableValue<?> value) {
        return value.subscribe(v -> setText(v == null ? "" : String.valueOf(v)));
    }
}
