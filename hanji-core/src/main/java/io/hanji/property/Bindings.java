package io.hanji.property;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Factory methods for derived (computed) observable values. */
public final class Bindings {

    private Bindings() {
    }

    /** A value computed by {@code computation} that is re-evaluated whenever one of {@code dependencies} changes. */
    public static <T> ObservableValue<T> create(Supplier<? extends T> computation, ObservableValue<?>... dependencies) {
        return new Derived<>(computation, dependencies);
    }

    public static <A, R> ObservableValue<R> map(ObservableValue<A> source, Function<? super A, ? extends R> mapper) {
        Objects.requireNonNull(mapper);
        return create(() -> mapper.apply(source.get()), source);
    }

    public static <A, B, R> ObservableValue<R> combine(ObservableValue<A> a, ObservableValue<B> b,
                                                       BiFunction<? super A, ? super B, ? extends R> combiner) {
        return create(() -> combiner.apply(a.get(), b.get()), a, b);
    }

    /** {@link String#format(String, Object...)} over observable arguments. */
    public static ObservableValue<String> format(String format, ObservableValue<?>... args) {
        return create(() -> {
            Object[] values = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                values[i] = args[i].get();
            }
            return String.format(format, values);
        }, args);
    }

    public static ObservableValue<Boolean> not(ObservableValue<Boolean> value) {
        return map(value, v -> !Boolean.TRUE.equals(v));
    }

    private static final class Derived<T> implements ObservableValue<T> {
        private final Supplier<? extends T> computation;
        private final List<ChangeListener<? super T>> listeners = new CopyOnWriteArrayList<>();
        private T value;

        Derived(Supplier<? extends T> computation, ObservableValue<?>[] dependencies) {
            this.computation = Objects.requireNonNull(computation);
            this.value = computation.get();
            for (ObservableValue<?> dep : dependencies) {
                dep.addListener((obs, oldValue, newValue) -> recompute());
            }
        }

        private void recompute() {
            T old = value;
            T now = computation.get();
            if (Objects.equals(old, now)) {
                return;
            }
            value = now;
            for (ChangeListener<? super T> l : listeners) {
                l.changed(this, old, now);
            }
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public void addListener(ChangeListener<? super T> listener) {
            listeners.add(Objects.requireNonNull(listener));
        }

        @Override
        public void removeListener(ChangeListener<? super T> listener) {
            listeners.remove(listener);
        }
    }
}
