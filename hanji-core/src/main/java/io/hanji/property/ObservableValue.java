package io.hanji.property;

import io.hanji.Registration;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A value that notifies listeners when it changes (comparable to {@code javafx.beans.value.ObservableValue}).
 */
public interface ObservableValue<T> {

    T get();

    void addListener(ChangeListener<? super T> listener);

    void removeListener(ChangeListener<? super T> listener);

    /** Calls {@code consumer} with the current value now and with every new value afterwards. */
    default Registration subscribe(Consumer<? super T> consumer) {
        consumer.accept(get());
        ChangeListener<T> listener = (obs, oldValue, newValue) -> consumer.accept(newValue);
        addListener(listener);
        return () -> removeListener(listener);
    }

    /** Returns a derived value that is recomputed whenever this value changes. */
    default <R> ObservableValue<R> map(Function<? super T, ? extends R> mapper) {
        return Bindings.map(this, mapper);
    }
}
