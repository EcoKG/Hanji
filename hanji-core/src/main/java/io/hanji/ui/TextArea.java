package io.hanji.ui;

import io.hanji.Element;

/** Multi-line text input. */
public class TextArea extends TextInputControl<TextArea> {

    public TextArea() {
        this(Element.create("textarea"));
        element.addClass("hj-input");
    }

    public TextArea(String text) {
        this();
        setText(text);
    }

    public TextArea(Element element) {
        super(element);
    }

    public TextArea setRows(int rows) {
        element.setAttribute("rows", rows);
        return this;
    }
}
