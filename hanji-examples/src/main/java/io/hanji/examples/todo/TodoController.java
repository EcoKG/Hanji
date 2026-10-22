package io.hanji.examples.todo;

import io.hanji.Element;
import io.hanji.Event;
import io.hanji.Hanji;
import io.hanji.JsCallable;
import io.hanji.UiField;
import io.hanji.UiHandler;
import io.hanji.Window;
import io.hanji.property.SimpleProperty;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller of {@code web/todo/index.html}: the HTML defines the layout, this class the behaviour
 * (like an FXML controller). The list is rendered from Java with {@link Element#create}.
 */
public class TodoController {

    enum Filter { ALL, ACTIVE, DONE }

    /** Shape of the add form; filled from the form's {@code name} attributes. */
    record NewTodo(String title, Todo.Priority priority) {
    }

    @UiField Window window;
    @UiField Element title;
    @UiField Element list;
    @UiField Element summary;
    @UiField Element clearDone;
    @UiField Element filterAll;
    @UiField Element filterActive;
    @UiField Element filterDone;

    private final TodoStore store;
    private final SimpleProperty<Filter> filter = new SimpleProperty<>(Filter.ALL);
    private final SimpleProperty<String> query = new SimpleProperty<>("");
    private boolean renderPending;

    public TodoController(TodoStore store) {
        this.store = store;
    }

    /** Called after the page is loaded and the fields are injected. */
    void initialize() {
        store.todos().addListener(l -> requestRender());
        filter.addListener((o, a, b) -> requestRender());
        query.addListener((o, a, b) -> requestRender());
        render();
        title.focus();
    }

    @UiHandler(value = "addForm", event = "submit")
    void add(Event e) {
        NewTodo form = e.getForm(NewTodo.class);
        if (form.title() == null || form.title().isBlank()) {
            title.focus();
            return;
        }
        store.add(form.title().trim(), form.priority());
        title.setValue("").focus();
    }

    @UiHandler(value = "list", event = "change", delegate = "input.toggle")
    void toggle(Event e) {
        Todo todo = todoOf(e);
        if (todo != null) {
            todo.setDone(e.isChecked());
            requestRender();
        }
    }

    @UiHandler(value = "list", event = "click", delegate = "button.delete")
    void delete(Event e) {
        Todo todo = todoOf(e);
        if (todo != null) {
            store.todos().remove(todo);
        }
    }

    @UiHandler(value = "list", event = "dblclick", delegate = "span.title")
    void rename(Event e) {
        Todo todo = todoOf(e);
        if (todo == null) {
            return;
        }
        window.prompt("할 일 수정", "새 제목을 입력하세요", todo.getTitle()).thenAccept(value -> {
            if (value != null && !value.isBlank()) {
                todo.setTitle(value.trim());
                requestRender();
            }
        });
    }

    @UiHandler({"filterAll", "filterActive", "filterDone"})
    void changeFilter(Event e) {
        filter.set(Filter.valueOf(e.getData("filter")));
    }

    @UiHandler(value = "search", event = "input", debounce = 150)
    void search(Event e) {
        query.set(e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT));
    }

    /** Also used by the "파일" menu of {@link TodoApp}. */
    @UiHandler("clearDone")
    void clearDone() {
        long count = store.todos().stream().filter(Todo::isDone).count();
        if (count == 0) {
            return;
        }
        window.confirm("완료 항목 삭제", "완료된 항목 " + count + "개를 삭제할까요?").thenAccept(ok -> {
            if (ok) {
                store.todos().removeIf(Todo::isDone);
            }
        });
    }

    /** Called from todo.js: {@code await hanji.call('exportTodos')}. */
    @JsCallable
    List<Todo> exportTodos() {
        return List.copyOf(store.todos());
    }

    // ------------------------------------------------------------------ rendering

    private void requestRender() {
        if (!renderPending) {
            renderPending = true;
            Hanji.defer(this::render);
        }
    }

    private void render() {
        renderPending = false;
        List<Todo> visible = store.todos().stream()
                .filter(t -> filter.get() == Filter.ALL || (filter.get() == Filter.DONE) == t.isDone())
                .filter(t -> query.get().isEmpty() || t.getTitle().toLowerCase(Locale.ROOT).contains(query.get()))
                .collect(Collectors.toList());

        list.clear();
        if (visible.isEmpty()) {
            list.append(Element.create("li").addClass("empty")
                    .setText(store.todos().isEmpty() ? "할 일을 추가해 보세요." : "조건에 맞는 항목이 없습니다."));
        }
        for (Todo t : visible) {
            list.append(Element.create("li")
                    .addClass("item", "p-" + t.getPriority().name().toLowerCase(Locale.ROOT))
                    .toggleClass("done", t.isDone())
                    .append(
                            Element.create("input").setAttribute("type", "checkbox").addClass("toggle")
                                    .setAttribute("data-id", t.getId()).setChecked(t.isDone()),
                            Element.create("span").addClass("title")
                                    .setAttribute("data-id", t.getId()).setText(t.getTitle()),
                            Element.create("span").addClass("badge").setText(label(t.getPriority())),
                            Element.create("button").addClass("delete").setAttribute("data-id", t.getId())
                                    .setAttribute("title", "삭제").setText("×")));
        }

        long done = store.todos().stream().filter(Todo::isDone).count();
        int total = store.todos().size();
        summary.setText(total == 0 ? "" : total + "개 중 " + done + "개 완료");
        clearDone.setEnabled(done > 0);
        filterAll.toggleClass("active", filter.get() == Filter.ALL);
        filterActive.toggleClass("active", filter.get() == Filter.ACTIVE);
        filterDone.toggleClass("active", filter.get() == Filter.DONE);
        window.publish("progress", Map.of("done", done, "total", total)); // drawn by todo.js
    }

    private Todo todoOf(Event e) {
        String id = e.getData("id");
        return id == null ? null : store.find(Long.parseLong(id));
    }

    private static String label(Todo.Priority p) {
        switch (p) {
            case HIGH: return "높음";
            case LOW: return "낮음";
            default: return "보통";
        }
    }
}
