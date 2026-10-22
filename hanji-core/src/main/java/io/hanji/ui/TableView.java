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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A table with typed columns, row selection and click-to-sort headers.
 * <pre>
 * TableView&lt;Person&gt; table = new TableView&lt;&gt;();
 * table.addColumn("Name", Person::name);
 * table.addColumn("Age", Person::age).setAlign("right");
 * table.getItems().setAll(people);
 * </pre>
 */
public class TableView<T> extends Component<TableView<T>> {

    /** A table column: header text and a value extractor. */
    public static final class Column<T> {
        private final TableView<T> table;
        private final String title;
        private final Function<? super T, ?> value;
        private Function<? super T, ?> cellFactory;
        private Comparator<? super T> comparator;
        private String width;
        private String align;
        private boolean sortable = true;

        private Column(TableView<T> table, String title, Function<? super T, ?> value) {
            this.table = table;
            this.title = title;
            this.value = value;
        }

        public String getTitle() {
            return title;
        }

        /** CSS width, e.g. "120px" or "30%". */
        public Column<T> setWidth(String width) {
            this.width = width;
            table.requestRender();
            return this;
        }

        /** CSS text-align: left, center, right. */
        public Column<T> setAlign(String align) {
            this.align = align;
            table.requestRender();
            return this;
        }

        public Column<T> setSortable(boolean sortable) {
            this.sortable = sortable;
            table.requestRender();
            return this;
        }

        /** Custom sort order (default: natural order of the column values). */
        public Column<T> setComparator(Comparator<? super T> comparator) {
            this.comparator = comparator;
            table.requestRender();
            return this;
        }

        /** Custom cell content: text or an element/component (default: the column value as text). */
        public Column<T> setCellFactory(Function<? super T, ?> cellFactory) {
            this.cellFactory = cellFactory;
            table.requestRender();
            return this;
        }

        Comparator<T> sortComparator() {
            if (comparator != null) {
                return comparator::compare;
            }
            return (a, b) -> compareValues(value.apply(a), value.apply(b));
        }
    }

    private final List<Column<T>> columns = new ArrayList<>();
    private final ObservableList<T> items = new ObservableList<>();
    private final SimpleProperty<T> selectedItem = new SimpleProperty<>();
    private final SimpleProperty<Integer> selectedIndex = new SimpleProperty<>(-1);
    private final Element head = Element.create("thead");
    private final Element body = Element.create("tbody");
    private final Map<Integer, Element> rows = new HashMap<>();
    private Column<T> sortColumn;
    private boolean ascending = true;
    private String placeholder = "";
    private Consumer<? super T> onRowActivated;
    private boolean renderPending;
    private boolean rendering;

    public TableView() {
        this(Element.create("table"));
    }

    /** Wraps an existing {@code <table>}; its content is replaced. */
    public TableView(Element element) {
        super(element);
        element.addClass("hj-table").clear().append(head, body);
        items.addListener(list -> requestRender());
        EventOptions rowClicks = EventOptions.of().delegate("tr[data-index]");
        body.on("click", rowClicks, this::selectFromEvent);
        body.on("dblclick", rowClicks, e -> {
            selectFromEvent(e);
            T item = selectedItem.get();
            if (onRowActivated != null && item != null) {
                onRowActivated.accept(item);
            }
        });
        head.on("click", EventOptions.of().delegate("th[data-col]"), e -> {
            String col = e.getData("col");
            if (col != null) {
                sortBy(columns.get(Integer.parseInt(col)));
            }
        });
        selectedIndex.addListener((obs, oldIndex, newIndex) -> highlight(oldIndex, newIndex));
    }

    public Column<T> addColumn(String title, Function<? super T, ?> value) {
        Column<T> column = new Column<>(this, title, Objects.requireNonNull(value));
        columns.add(column);
        requestRender();
        return column;
    }

    public List<Column<T>> getColumns() {
        return List.copyOf(columns);
    }

    public ObservableList<T> getItems() {
        return items;
    }

    public TableView<T> setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        requestRender();
        return this;
    }

    /** Called on double click of a row. */
    public TableView<T> setOnRowActivated(Consumer<? super T> handler) {
        this.onRowActivated = handler;
        return this;
    }

    /** Sorts by {@code column}; sorting by the same column again toggles the direction. */
    public TableView<T> sortBy(Column<T> column) {
        if (column == null || !column.sortable) {
            return this;
        }
        if (column == sortColumn) {
            ascending = !ascending;
        } else {
            sortColumn = column;
            ascending = true;
        }
        requestRender();
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

    /** Index of the selected item in {@link #getItems()} (model index, independent of sorting). */
    public int getSelectedIndex() {
        return selectedIndex.get();
    }

    public TableView<T> select(int index) {
        if (index < 0 || index >= items.size()) {
            return clearSelection();
        }
        selectedIndex.set(index);
        selectedItem.set(items.get(index));
        return this;
    }

    public TableView<T> select(T item) {
        return select(items.indexOf(item));
    }

    public TableView<T> clearSelection() {
        selectedIndex.set(-1);
        selectedItem.set(null);
        return this;
    }

    public TableView<T> refresh() {
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
        Element oldRow = oldIndex == null ? null : rows.get(oldIndex);
        Element newRow = newIndex == null ? null : rows.get(newIndex);
        if (oldRow != null) {
            oldRow.removeClass("hj-selected");
        }
        if (newRow != null) {
            newRow.addClass("hj-selected");
        }
    }

    void requestRender() {
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

        head.clear();
        Element headerRow = Element.create("tr");
        for (int c = 0; c < columns.size(); c++) {
            Column<T> col = columns.get(c);
            Element th = Element.create("th").setText(col.title).setAttribute("data-col", c);
            if (col.sortable) {
                th.addClass("hj-sortable");
            }
            if (col == sortColumn) {
                th.addClass(ascending ? "hj-sort-asc" : "hj-sort-desc");
            }
            if (col.width != null) {
                th.setStyle("width", col.width);
            }
            if (col.align != null) {
                th.setStyle("text-align", col.align);
            }
            headerRow.append(th);
        }
        head.append(headerRow);

        body.clear();
        rows.clear();
        if (items.isEmpty()) {
            Element td = Element.create("td").addClass("hj-empty")
                    .setAttribute("colspan", Math.max(1, columns.size())).setText(placeholder);
            body.append(Element.create("tr").append(td));
            return;
        }
        List<Integer> order = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        if (sortColumn != null) {
            Comparator<T> cmp = sortColumn.sortComparator();
            Comparator<Integer> byItem = (a, b) -> cmp.compare(items.get(a), items.get(b));
            order.sort(ascending ? byItem : byItem.reversed());
        }
        int selected = selectedIndex.get();
        List<Element> rendered = new ArrayList<>(order.size());
        for (int modelIndex : order) {
            T item = items.get(modelIndex);
            Element tr = Element.create("tr").setAttribute("data-index", modelIndex);
            if (modelIndex == selected) {
                tr.addClass("hj-selected");
            }
            for (Column<T> col : columns) {
                Element td = Element.create("td");
                Object content = col.cellFactory != null ? col.cellFactory.apply(item) : col.value.apply(item);
                if (content instanceof HasElement) {
                    td.append((HasElement) content);
                } else {
                    td.setText(content == null ? "" : String.valueOf(content));
                }
                if (col.align != null) {
                    td.setStyle("text-align", col.align);
                }
                tr.append(td);
            }
            rows.put(modelIndex, tr);
            rendered.add(tr);
        }
        body.append(rendered.toArray(new HasElement[0]));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareValues(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }
}
