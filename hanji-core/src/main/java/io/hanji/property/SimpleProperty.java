package io.hanji.property;

import io.hanji.Registration;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link Property} implementation. Listeners are only notified when the value actually changes
 * (compared with {@link Objects#equals(Object, Object)}).
 */
public class SimpleProperty<T> implements Property<T> {

    private final List<ChangeListener<? super T>> listeners = new CopyOnWriteArrayList<>();
    private T value;
    private ObservableValue<? extends T> boundTo;
    private ChangeListener<T> bindListener;

    public SimpleProperty() {
    }

    public SimpleProperty(T initialValue) {
        this.value = initialValue;
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public void set(T newValue) {
        if (boundTo != null) {
            throw new IllegalStateException("A bound property cannot be set; call unbind() first");
        }
        update(newValue);
    }

    private void update(T newValue) {
        T old = value;
        if (Objects.equals(old, newValue)) {
            return;
        }
        value = newValue;
        for (ChangeListener<? super T> l : listeners) {
            l.changed(this, old, newValue);
        }
    }

    @Override
    public void addListener(ChangeListener<? super T> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void removeListener(ChangeListener<? super T> listener) {
        listeners.remove(listener);
    }

    @Override
    public void bind(ObservableValue<? extends T> source) {
        Objects.requireNonNull(source);
        unbind();
        boundTo = source;
        bindListener = (obs, oldValue, newValue) -> update(newValue);
        source.addListener(bindListener);
        update(source.get());
    }

    @Override
    public void unbind() {
        if (boundTo != null) {
            boundTo.removeListener(bindListener);
            boundTo = null;
            bindListener = null;
        }
    }

    @Override
    public boolean isBound() {
        return boundTo != null;
    }

    @Override
    public Registration bindBidirectional(Property<T> other) {
        Objects.requireNonNull(other);
        boolean[] updating = {false};
        set(other.get());
        ChangeListener<T> toOther = (obs, oldValue, newValue) -> {
            if (!updating[0]) {
                updating[0] = true;
                try {
                    other.set(newValue);
                } finally {
                    updating[0] = false;
                }
            }
        };
        ChangeListener<T> fromOther = (obs, oldValue, newValue) -> {
            if (!updating[0]) {
                updating[0] = true;
                try {
                    set(newValue);
                } finally {
                    updating[0] = false;
                }
            }
        };
        addListener(toOther);
        other.addListener(fromOther);
        return () -> {
            removeListener(toOther);
            other.removeListener(fromOther);
        };
    }

    @Override
    public String toString() {
        return "Property[" + value + "]";
    }
}
