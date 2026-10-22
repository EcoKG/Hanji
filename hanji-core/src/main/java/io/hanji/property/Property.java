package io.hanji.property;

import io.hanji.Registration;

/** A writable {@link ObservableValue} that can be bound to other values. */
public interface Property<T> extends ObservableValue<T> {

    /** Sets the value. Throws {@link IllegalStateException} while the property is bound. */
    void set(T value);

    /** One-way binding: this property follows {@code source} until {@link #unbind()} is called. */
    void bind(ObservableValue<? extends T> source);

    void unbind();

    boolean isBound();

    /** Keeps this property and {@code other} in sync in both directions. This property first takes other's value. */
    Registration bindBidirectional(Property<T> other);
}
