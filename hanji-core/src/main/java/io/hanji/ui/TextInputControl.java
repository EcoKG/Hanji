package io.hanji.ui;

import io.hanji.Element;
import io.hanji.Registration;
import io.hanji.property.Property;
import io.hanji.property.SimpleProperty;

/** Common base of {@link TextField}, {@link PasswordField} and {@link TextArea}: a two-way synced text property. */
public abstract class TextInputControl<C extends TextInputControl<C>> extends Component<C> {

    private final SimpleProperty<String> text;
    private boolean fromPage;

    protected TextInputControl(Element element) {
        super(element);
        text = new SimpleProperty<>(element.getValue());
        text.addListener((obs, oldValue, newValue) -> {
            if (!fromPage) {
                element.setValue(newValue);
            }
        });
        element.on("input", e -> {
            fromPage = true;
            try {
                text.set(e.getValue() == null ? "" : e.getValue());
            } finally {
                fromPage = false;
            }
        });
    }

    /** The current text; updated on every keystroke, and writing it updates the field. */
    public Property<String> textProperty() {
        return text;
    }

    public String getText() {
        return text.get() == null ? "" : text.get();
    }

    public C setText(String value) {
        text.set(value == null ? "" : value);
        return self();
    }

    public C setPlaceholder(String placeholder) {
        element.setAttribute("placeholder", placeholder);
        return self();
    }

    public C setEditable(boolean editable) {
        element.setProperty("readOnly", !editable);
        return self();
    }

    public C selectAll() {
        element.invoke("select");
        return self();
    }

    /** Two-way binding of the text to another property. */
    public Registration bindText(Property<String> other) {
        return text.bindBidirectional(other);
    }
}
