package io.hanji.property;

/** Notified after an {@link ObservableList} was modified. */
@FunctionalInterface
public interface ListChangeListener<T> {

    void onChanged(ObservableList<? extends T> list);
}
