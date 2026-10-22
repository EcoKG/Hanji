package io.hanji.property;

import io.hanji.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyTest {

    @Test
    void notifiesOnlyOnChange() {
        SimpleProperty<String> p = new SimpleProperty<>("a");
        List<String> seen = new ArrayList<>();
        p.addListener((obs, oldValue, newValue) -> seen.add(oldValue + "->" + newValue));
        p.set("a");
        p.set("b");
        p.set(null);
        assertEquals(List.of("a->b", "b->null"), seen);
    }

    @Test
    void bindAndUnbind() {
        SimpleProperty<Integer> source = new SimpleProperty<>(1);
        SimpleProperty<Integer> target = new SimpleProperty<>(0);
        target.bind(source);
        assertEquals(1, target.get());
        source.set(5);
        assertEquals(5, target.get());
        assertThrows(IllegalStateException.class, () -> target.set(3));
        target.unbind();
        source.set(7);
        assertEquals(5, target.get());
    }

    @Test
    void bidirectional() {
        SimpleProperty<String> a = new SimpleProperty<>("x");
        SimpleProperty<String> b = new SimpleProperty<>("y");
        Registration r = a.bindBidirectional(b);
        assertEquals("y", a.get());
        a.set("1");
        assertEquals("1", b.get());
        b.set("2");
        assertEquals("2", a.get());
        r.remove();
        a.set("3");
        assertEquals("2", b.get());
    }

    @Test
    void derivedValues() {
        SimpleProperty<Integer> a = new SimpleProperty<>(2);
        SimpleProperty<Integer> b = new SimpleProperty<>(3);
        ObservableValue<Integer> sum = Bindings.combine(a, b, Integer::sum);
        ObservableValue<String> text = sum.map(s -> "sum=" + s);
        List<String> seen = new ArrayList<>();
        Registration r = text.subscribe(seen::add);
        a.set(10);
        r.remove();
        b.set(0);
        assertEquals(List.of("sum=5", "sum=13"), seen);
        assertEquals("sum=10", text.get());
        assertEquals("2 and 3", Bindings.format("%d and %d", new SimpleProperty<>(2), new SimpleProperty<>(3)).get());
    }

    @Test
    void observableListBatchesNotifications() {
        ObservableList<String> list = new ObservableList<>();
        int[] count = {0};
        list.addListener(l -> count[0]++);
        list.add("a");
        list.addAll(List.of("b", "c"));
        list.setAll("x", "y", "z", "w");
        list.removeIf(s -> s.compareTo("y") < 0);
        list.batch(() -> {
            list.add("1");
            list.add("2");
        });
        list.subList(0, 1).clear();
        assertEquals(6, count[0]);
        assertEquals(List.of("z", "1", "2"), list);
        assertEquals(3, list.sizeProperty().get());
        assertTrue(list.contains("z"));
    }
}
