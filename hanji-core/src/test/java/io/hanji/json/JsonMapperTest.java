package io.hanji.json;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonMapperTest {

    enum Level { LOW, HIGH }

    record Address(String city, int zip) {
    }

    record Person(String name, int age, List<Address> addresses, Level level, Optional<String> nick) {
    }

    public static class Bean {
        private String title;
        private double price;

        public Bean() {
        }

        public String getTitle() {
            return title;
        }

        public double getPrice() {
            return price;
        }

        public boolean isExpensive() {
            return price > 100;
        }
    }

    @Test
    void recordRoundTrip() {
        Person p = new Person("Kim", 30, List.of(new Address("Seoul", 4500)), Level.HIGH, Optional.of("k"));
        Object plain = JsonMapper.toPlain(p);
        Object parsed = Json.parse(Json.stringify(plain));
        assertEquals(p, JsonMapper.fromJson(parsed, Person.class));
    }

    @Test
    void pojoToPlainUsesGetters() {
        Bean b = JsonMapper.fromJson(Map.of("title", "Book", "price", 120), Bean.class);
        assertEquals("Book", b.getTitle());
        assertEquals(Map.of("expensive", true, "price", 120.0, "title", "Book"), JsonMapper.toPlain(b));
    }

    @Test
    void convertsGenericCollectionsAndArrays() throws Exception {
        Type listOfInts = JsonMapperTest.class.getDeclaredMethod("ints").getGenericReturnType();
        assertEquals(List.of(1, 2, 3), JsonMapper.fromJson(List.of(1L, 2.0, "3"), listOfInts));
        assertArrayEquals(new long[]{4, 5}, (long[]) JsonMapper.fromJson(List.of(4L, 5L), long[].class));
    }

    @Test
    void nullBecomesPrimitiveDefault() {
        assertEquals(0, (int) JsonMapper.fromJson(null, int.class));
        assertEquals(false, JsonMapper.fromJson(null, boolean.class));
    }

    @SuppressWarnings("unused")
    private static List<Integer> ints() {
        return null;
    }
}
