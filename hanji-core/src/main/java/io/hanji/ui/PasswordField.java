package io.hanji.ui;

import io.hanji.Element;

/** A {@link TextField} that masks its input. */
public class PasswordField extends TextField {

    public PasswordField() {
        super(Element.create("input").setAttribute("type", "password"));
        element.addClass("hj-input");
    }

    public PasswordField(Element element) {
        super(element);
    }
}
