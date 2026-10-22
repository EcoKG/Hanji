package io.hanji.ui;

import io.hanji.Element;
import io.hanji.EventHandler;
import io.hanji.EventOptions;
import io.hanji.Registration;

/** Single-line text input. {@link #setOnAction} fires on Enter. */
public class TextField extends TextInputControl<TextField> {

    private Registration action;

    public TextField() {
        this(Element.create("input").setAttribute("type", "text"));
        element.addClass("hj-input");
    }

    public TextField(String text) {
        this();
        setText(text);
    }

    public TextField(Element element) {
        super(element);
    }

    /** Called when the user presses Enter ({@code null} removes the handler). */
    public TextField setOnAction(EventHandler handler) {
        if (action != null) {
            action.remove();
            action = null;
        }
        if (handler != null) {
            action = element.on("keydown", EventOptions.of().keys("Enter"), handler);
        }
        return this;
    }
}
