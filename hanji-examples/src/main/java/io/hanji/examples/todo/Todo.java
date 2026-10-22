package io.hanji.examples.todo;

/** A todo item (mutable: title and done state change). */
public final class Todo {

    public enum Priority { LOW, NORMAL, HIGH }

    private final long id;
    private String title;
    private Priority priority;
    private boolean done;

    public Todo(long id, String title, Priority priority) {
        this.id = id;
        this.title = title;
        this.priority = priority;
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Priority getPriority() {
        return priority;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }
}
