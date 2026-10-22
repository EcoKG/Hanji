package io.hanji.ui;

import io.hanji.Element;
import io.hanji.HasElement;

/** A component that holds other components or elements. */
public abstract class Container<C extends Container<C>> extends Component<C> {

    protected Container(Element element) {
        super(element);
    }

    public C add(HasElement... children) {
        element.append(children);
        return self();
    }

    /** Removes all children. */
    public C clear() {
        element.clear();
        return self();
    }

    /** Space between children in pixels. */
    public C setGap(int pixels) {
        element.setStyle("gap", pixels + "px");
        return self();
    }

    public C setPadding(int pixels) {
        element.setStyle("padding", pixels + "px");
        return self();
    }

    /** CSS {@code align-items}: start, center, end, stretch, baseline. */
    public C setAlignment(String alignItems) {
        element.setStyle("align-items", alignItems);
        return self();
    }

    /** CSS {@code justify-content}: start, center, end, space-between ... */
    public C setJustify(String justifyContent) {
        element.setStyle("justify-content", justifyContent);
        return self();
    }
}
