package io.hanji.ui;

import io.hanji.Element;
import io.hanji.Event;
import io.hanji.EventOptions;
import io.hanji.Hanji;
import io.hanji.HasElement;
import io.hanji.property.ObservableList;
import io.hanji.property.ObservableValue;
import io.hanji.property.SimpleProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A selectable list. Each item is rendered by the cell factory, which may return text or an element/component.
 * <pre>
 * ListView&lt;Person&gt; people = new ListView&lt;&gt;();
 * people.setCellFactory(p -&gt; p.name() + " (" + p.age() + ")");
 * people.getItems().setAll(repository.findAll());
 * people.selectedItemProperty().addListener((o, a, b) -&gt; show(b));
 * </pre>
 * Changes to {@link #getItems()} are coalesced and rendered once per UI task.
 */
public class ListView<T> extends Component<ListView<T>> {

    private final ObservableList<T> items = new ObservableList<>();
    private final SimpleProperty<T> selectedItem = new SimpleProperty<>();
    private final SimpleProperty<Integer> selectedIndex = new SimpleProperty<>(-1);
    private final List<Element> cells = new ArrayList<>();
    private Function<? super T, ?> cellFactory = item -> item;
    private Consumer<? super T> onItemActivated;
    private String placeholder;
    private boolean renderPending;
    private boolean rendering;

    public ListView() {
        this(Element.create("ul"));
    }

    public ListView(Collection<? extends T> items) {
        this();
        this.items.setAll(items);
    }

    /** Wraps an existing {@code <ul>}/{@code <ol>}/{@code <div>}; its children are replaced by the items. */
    public ListView(Element element) {
        super(element);
        element.addClass("hj-list");
        items.addListener(list -> requestRender());
        EventOptions rows = EventOptions.of().delegate("[data-index]");
        element.on("click", rows, this::selectFromEvent);
        element.on("dblclick", rows, e -> {
            selectFromEvent(e);
            T item = selectedItem.get();
            if (onItemActivated != null && item != null) {
                onItemActivated.accept(item);
            }
        });
        selectedIndex.addListener((obs, oldIndex, newIndex) -> highlight(oldIndex, newIndex));
    }

    public ObservableList<T> getItems() {
        return items;
    }

    /** Renders an item as text ({@code String}/any object) or as a node ({@link HasElement}). */
    public ListView<T> setCellFactory(Function<? super T, ?> cellFactory) {
        this.cellFactory = Objects.requireNonNull(cellFactory);
        requestRender();
        return this;
    }

    /** Text shown when the list is empty. */
    public ListView<T> setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        requestRender();
        return this;
    }

    /** Called on double click of an item. */
    public ListView<T> setOnItemActivated(Consumer<? super T> handler) {
        this.onItemActivated = handler;
        return this;
    }

    public ObservableValue<T> selectedItemProperty() {
        return selectedItem;
    }

    public ObservableValue<Integer> selectedIndexProperty() {
        return selectedIndex;
    }

    public T getSelectedItem() {
        return selectedItem.get();
    }

    public int getSelectedIndex() {
        return selectedIndex.get();
    }

    public ListView<T> select(int index) {
        if (index < 0 || index >= items.size()) {
            return clearSelection();
        }
        selectedIndex.set(index);
        selectedItem.set(items.get(index));
        return this;
    }

    public ListView<T> select(T item) {
        return select(items.indexOf(item));
    }

    public ListView<T> clearSelection() {
        selectedIndex.set(-1);
        selectedItem.set(null);
        return this;
    }

    /** Re-renders all items (e.g. after mutating an item object in place). */
    public ListView<T> refresh() {
        requestRender();
        return this;
    }

    private void selectFromEvent(Event e) {
        String index = e.getData("index");
        if (index != null) {
            select(Integer.parseInt(index));
        }
    }

    private void highlight(Integer oldIndex, Integer newIndex) {
        if (rendering || renderPending) {
            return;
        }
        if (oldIndex != null && oldIndex >= 0 && oldIndex < cells.size()) {
            cells.get(oldIndex).removeClass("hj-selected");
        }
        if (newIndex != null && newIndex >= 0 && newIndex < cells.size()) {
            cells.get(newIndex).addClass("hj-selected");
        }
    }

    private void requestRender() {
        if (!renderPending) {
            renderPending = true;
            Hanji.defer(this::render);
        }
    }

    private void render() {
        renderPending = false;
        rendering = true;
        try {
            int index = selectedItem.get() == null ? -1 : items.indexOf(selectedItem.get());
            if (index < 0) {
                clearSelection();
            } else {
                selectedIndex.set(index);
            }
        } finally {
            rendering = false;
        }
        element.clear();
        cells.clear();
        if (items.isEmpty()) {
            if (placeholder != null) {
                element.append(Element.create("li").addClass("hj-list-empty").setText(placeholder));
            }
            return;
        }
        int selected = selectedIndex.get();
        for (int i = 0; i < items.size(); i++) {
            Element cell = Element.create("li").addClass("hj-list-item").setAttribute("data-index", i);
            if (i == selected) {
                cell.addClass("hj-selected");
            }
            Object content = cellFactory.apply(items.get(i));
            if (content instanceof HasElement) {
                cell.append((HasElement) content);
            } else {
                cell.setText(content == null ? "" : String.valueOf(content));
            }
            cells.add(cell);
        }
        element.append(cells.toArray(new HasElement[0]));
    }
}
