package io.hanji.examples.todo;

import io.hanji.property.ObservableList;

/** Application state that outlives page reloads (controllers are recreated, the store is not). */
public final class TodoStore {

    private final ObservableList<Todo> todos = new ObservableList<>();
    private long nextId = 1;

    public ObservableList<Todo> todos() {
        return todos;
    }

    public Todo add(String title, Todo.Priority priority) {
        Todo todo = new Todo(nextId++, title, priority);
        todos.add(todo);
        return todo;
    }

    public Todo find(long id) {
        return todos.stream().filter(t -> t.getId() == id).findFirst().orElse(null);
    }
}
