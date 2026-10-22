package io.hanji.ui;

import io.hanji.Element;
import io.hanji.HasElement;

/** Lays out children horizontally (CSS flex row). */
public class HBox extends Container<HBox> {

    public HBox(HasElement... children) {
        this(Element.create("div"));
        add(children);
    }

    public HBox(Element element) {
        super(element);
        element.addClass("hj-hbox");
    }
}
