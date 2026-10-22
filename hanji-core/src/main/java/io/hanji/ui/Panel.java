package io.hanji.ui;

import io.hanji.Element;
import io.hanji.HasElement;

/** A plain container with any tag ({@code div}, {@code section}, {@code form} ...), styled by your CSS. */
public class Panel extends Container<Panel> {

    public Panel(String tag, HasElement... children) {
        this(Element.create(tag));
        add(children);
    }

    public Panel(Element element) {
        super(element);
    }
}
