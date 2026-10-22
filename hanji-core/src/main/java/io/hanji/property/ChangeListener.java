package io.hanji.property;

/** Receives value changes of an {@link ObservableValue}. */
@FunctionalInterface
public interface ChangeListener<T> {

    void changed(ObservableValue<? extends T> observable, T oldValue, T newValue);
}
