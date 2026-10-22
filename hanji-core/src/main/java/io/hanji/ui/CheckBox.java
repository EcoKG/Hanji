package io.hanji.ui;

import io.hanji.Element;
import io.hanji.EventHandler;
import io.hanji.Registration;
import io.hanji.property.Property;
import io.hanji.property.SimpleProperty;

/** A checkbox with an optional caption. Wrapping an existing {@code <input type=checkbox>} is supported. */
public class CheckBox extends Component<CheckBox> {

    private final Element input;
    private final Element caption;
    private final SimpleProperty<Boolean> selected;
    private final SimpleProperty<String> text = new SimpleProperty<>();
    private boolean fromPage;
    private Registration action;

    public CheckBox() {
        this("");
    }

    public CheckBox(String text) {
        this(Element.create("label").addClass("hj-checkbox"),
                Element.create("input").setAttribute("type", "checkbox"),
                Element.create("span"));
        setText(text);
    }

    public CheckBox(String text, boolean selected) {
        this(text);
        setSelected(selected);
    }

    /** Wraps an existing {@code <input type="checkbox">}. */
    public CheckBox(Element input) {
        this(input, input, null);
    }

    private CheckBox(Element root, Element input, Element caption) {
        super(root);
        this.input = input;
        this.caption = caption;
        if (caption != null) {
            root.append(input, caption);
            text.addListener((obs, oldValue, newValue) -> caption.setText(newValue));
        }
        selected = new SimpleProperty<>(input.isChecked());
        selected.addListener((obs, oldValue, newValue) -> {
            if (!fromPage) {
                input.setChecked(Boolean.TRUE.equals(newValue));
            }
        });
        input.on("change", e -> {
            fromPage = true;
            try {
                selected.set(e.isChecked());
            } finally {
                fromPage = false;
            }
        });
    }

    @Override
    protected Element control() {
        return input;
    }

    public Property<Boolean> selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return Boolean.TRUE.equals(selected.get());
    }

    public CheckBox setSelected(boolean value) {
        selected.set(value);
        return this;
    }

    public Property<String> textProperty() {
        return text;
    }

    public CheckBox setText(String value) {
        text.set(value == null ? "" : value);
        return this;
    }

    /** Called when the user toggles the checkbox ({@code null} removes the handler). */
    public CheckBox setOnAction(EventHandler handler) {
        if (action != null) {
            action.remove();
            action = null;
        }
        if (handler != null) {
            action = input.on("change", handler);
        }
        return this;
    }
}
