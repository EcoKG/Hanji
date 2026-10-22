package io.hanji.ui;

import io.hanji.Element;
import io.hanji.EventHandler;
import io.hanji.HasElement;
import io.hanji.Registration;
import io.hanji.property.ObservableValue;

import java.util.Objects;

/**
 * Base class of the built-in components. A component wraps one root {@link Element} and offers a typed
 * API on top of it. Components can be created in Java ({@code new Button("OK")}) or wrap an element of
 * an HTML page ({@code new Button(window.byId("ok"))}, or {@code @UiField Button ok;}).
 *
 * @param <C> the concrete type, so fluent setters return it
 */
public abstract class Component<C extends Component<C>> implements HasElement {

    protected final Element element;

    protected Component(Element element) {
        this.element = Objects.requireNonNull(element, "element");
    }

    @Override
    public Element element() {
        return element;
    }

    @SuppressWarnings("unchecked")
    protected final C self() {
        return (C) this;
    }

    /** The element that receives {@code disabled} (differs from the root for e.g. {@link CheckBox}). */
    protected Element control() {
        return element;
    }

    public String getId() {
        return element.getId();
    }

    public C addClass(String... classNames) {
        element.addClass(classNames);
        return self();
    }

    public C removeClass(String... classNames) {
        element.removeClass(classNames);
        return self();
    }

    public C setStyle(String property, String value) {
        element.setStyle(property, value);
        return self();
    }

    public C setVisible(boolean visible) {
        element.setVisible(visible);
        return self();
    }

    public boolean isVisible() {
        return element.isVisible();
    }

    public C setEnabled(boolean enabled) {
        control().setEnabled(enabled);
        return self();
    }

    public C setTooltip(String text) {
        element.setTooltip(text);
        return self();
    }

    /** Lets the component fill the remaining space in a {@link VBox}/{@link HBox}. */
    public C setGrow(boolean grow) {
        element.toggleClass("hj-grow", grow);
        return self();
    }

    public C focus() {
        control().focus();
        return self();
    }

    public Registration bindVisible(ObservableValue<Boolean> visible) {
        return element.bindVisible(visible);
    }

    public Registration bindEnabled(ObservableValue<Boolean> enabled) {
        return control().bindEnabled(enabled);
    }

    public Registration on(String eventType, EventHandler handler) {
        return element.on(eventType, handler);
    }
}
