package io.hanji.ui;

import io.hanji.Element;
import io.hanji.HasElement;

/** Lays out children vertically (CSS flex column). */
public class VBox extends Container<VBox> {

    public VBox(HasElement... children) {
        this(Element.create("div"));
        add(children);
    }

    public VBox(Element element) {
        super(element);
        element.addClass("hj-vbox");
    }
}
