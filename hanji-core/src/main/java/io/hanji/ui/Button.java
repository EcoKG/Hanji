package io.hanji.ui;

import io.hanji.Element;
import io.hanji.EventHandler;
import io.hanji.Registration;
import io.hanji.property.Property;
import io.hanji.property.SimpleProperty;

/** A push button. {@link #setOnAction} replaces the previous action handler (JavaFX semantics). */
public class Button extends Component<Button> {

    private final SimpleProperty<String> text = new SimpleProperty<>();
    private Registration action;

    public Button() {
        this("");
    }

    public Button(String text) {
        this(Element.create("button").setAttribute("type", "button"));
        element.addClass("hj-button");
        setText(text);
    }

    public Button(String text, EventHandler onAction) {
        this(text);
        setOnAction(onAction);
    }

    public Button(Element element) {
        super(element);
        text.addListener((obs, oldValue, newValue) -> element.setText(newValue));
    }

    public Property<String> textProperty() {
        return text;
    }

    public String getText() {
        return text.get() == null ? "" : text.get();
    }

    public Button setText(String value) {
        text.set(value == null ? "" : value);
        return this;
    }

    /** Sets the click handler ({@code null} removes it). */
    public Button setOnAction(EventHandler handler) {
        if (action != null) {
            action.remove();
            action = null;
        }
        if (handler != null) {
            action = element.on("click", handler);
        }
        return this;
    }

    /** Highlights the button as the main action. */
    public Button setPrimary(boolean primary) {
        element.toggleClass("hj-primary", primary);
        return this;
    }
}
