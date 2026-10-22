package io.hanji.property;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * A {@link List} that notifies {@link ListChangeListener}s after each modification.
 * Bulk operations ({@link #setAll}, {@link #addAll}, {@link #clear}, {@link #removeIf}, {@link #batch})
 * produce a single notification.
 */
public class ObservableList<T> extends AbstractList<T> implements RandomAccess {

    private final ArrayList<T> data = new ArrayList<>();
    private final List<ListChangeListener<? super T>> listeners = new CopyOnWriteArrayList<>();
    private final SimpleProperty<Integer> size = new SimpleProperty<>(0);
    private int batchDepth;
    private boolean changedInBatch;

    public ObservableList() {
    }

    public ObservableList(Collection<? extends T> initial) {
        data.addAll(initial);
        size.set(data.size());
    }

    @SafeVarargs
    public static <T> ObservableList<T> of(T... items) {
        return new ObservableList<>(Arrays.asList(items));
    }

    public void addListener(ListChangeListener<? super T> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(ListChangeListener<? super T> listener) {
        listeners.remove(listener);
    }

    /** Observable view of {@link #size()}. */
    public ObservableValue<Integer> sizeProperty() {
        return size;
    }

    /** Runs {@code changes} and fires a single notification afterwards (if anything changed). */
    public void batch(Runnable changes) {
        batchDepth++;
        try {
            changes.run();
        } finally {
            batchDepth--;
            if (batchDepth == 0 && changedInBatch) {
                changedInBatch = false;
                fire();
            }
        }
    }

    /** Replaces the whole content with one notification. */
    public void setAll(Collection<? extends T> items) {
        List<T> copy = new ArrayList<>(items);
        modCount++;
        data.clear();
        data.addAll(copy);
        fire();
    }

    @SafeVarargs
    public final void setAll(T... items) {
        setAll(Arrays.asList(items));
    }

    private void fire() {
        if (batchDepth > 0) {
            changedInBatch = true;
            return;
        }
        size.set(data.size());
        for (ListChangeListener<? super T> l : listeners) {
            l.onChanged(this);
        }
    }

    @Override
    public T get(int index) {
        return data.get(index);
    }

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public T set(int index, T element) {
        T old = data.set(index, element);
        fire();
        return old;
    }

    @Override
    public void add(int index, T element) {
        modCount++;
        data.add(index, element);
        fire();
    }

    @Override
    public T remove(int index) {
        modCount++;
        T old = data.remove(index);
        fire();
        return old;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        if (c.isEmpty()) {
            return false;
        }
        modCount++;
        data.addAll(c);
        fire();
        return true;
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        if (c.isEmpty()) {
            return false;
        }
        modCount++;
        data.addAll(index, c);
        fire();
        return true;
    }

    @Override
    public void clear() {
        if (data.isEmpty()) {
            return;
        }
        modCount++;
        data.clear();
        fire();
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        if (fromIndex >= toIndex) {
            return;
        }
        modCount++;
        data.subList(fromIndex, toIndex).clear();
        fire();
    }

    @Override
    public boolean removeIf(Predicate<? super T> filter) {
        modCount++;
        boolean changed = data.removeIf(filter);
        if (changed) {
            fire();
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return removeIf(c::contains);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return removeIf(item -> !c.contains(item));
    }
}
