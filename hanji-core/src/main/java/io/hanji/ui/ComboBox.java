package io.hanji.ui;

import io.hanji.Element;
import io.hanji.EventHandler;
import io.hanji.Hanji;
import io.hanji.Registration;
import io.hanji.property.ObservableList;
import io.hanji.property.Property;
import io.hanji.property.SimpleProperty;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/**
 * Drop-down selection ({@code <select>}) over typed items.
 * <pre>
 * ComboBox&lt;City&gt; city = new ComboBox&lt;&gt;(cities).setConverter(City::name);
 * city.valueProperty().addListener((o, a, b) -&gt; ...);
 * </pre>
 */
public class ComboBox<T> extends Component<ComboBox<T>> {

    private final ObservableList<T> items = new ObservableList<>();
    private final SimpleProperty<T> value = new SimpleProperty<>();
    private Function<? super T, String> converter = String::valueOf;
    private String placeholder;
    private boolean fromPage;
    private boolean renderPending;
    private Registration action;

    public ComboBox() {
        this(Element.create("select"));
        element.addClass("hj-input");
    }

    public ComboBox(Collection<? extends T> items) {
        this();
        this.items.setAll(items);
    }

    /** Wraps an existing {@code <select>}; its options are replaced by {@link #getItems()}. */
    public ComboBox(Element element) {
        super(element);
        items.addListener(list -> requestRender());
        value.addListener((obs, oldValue, newValue) -> {
            if (!fromPage) {
                syncSelection();
            }
        });
        element.on("change", e -> {
            fromPage = true;
            try {
                int index = parseIndex(e.getValue());
                value.set(index >= 0 && index < items.size() ? items.get(index) : null);
            } finally {
                fromPage = false;
            }
        });
    }

    public ObservableList<T> getItems() {
        return items;
    }

    /** The selected item ({@code null} = nothing selected). */
    public Property<T> valueProperty() {
        return value;
    }

    public T getValue() {
        return value.get();
    }

    public ComboBox<T> setValue(T newValue) {
        value.set(newValue);
        return this;
    }

    /** How items are shown (default {@code String.valueOf}). */
    public ComboBox<T> setConverter(Function<? super T, String> converter) {
        this.converter = Objects.requireNonNull(converter);
        requestRender();
        return this;
    }

    /** Text of an empty first option shown when nothing is selected. */
    public ComboBox<T> setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        requestRender();
        return this;
    }

    /** Called when the user selects an item ({@code null} removes the handler). */
    public ComboBox<T> setOnAction(EventHandler handler) {
        if (action != null) {
            action.remove();
            action = null;
        }
        if (handler != null) {
            action = element.on("change", handler);
        }
        return this;
    }

    private void requestRender() {
        if (!renderPending) {
            renderPending = true;
            Hanji.defer(this::render);
        }
    }

    private void render() {
        renderPending = false;
        element.clear();
        if (placeholder != null) {
            element.append(Element.create("option").setAttribute("value", "").setText(placeholder));
        }
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            element.append(Element.create("option").setAttribute("value", i)
                    .setText(item == null ? "" : converter.apply(item)));
        }
        syncSelection();
    }

    private void syncSelection() {
        if (renderPending) {
            return;
        }
        int index = value.get() == null ? -1 : items.indexOf(value.get());
        element.setValue(index < 0 ? "" : String.valueOf(index));
    }

    private static int parseIndex(String s) {
        try {
            return s == null || s.isEmpty() ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
