package io.hanji.json;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void parsesAllValueTypes() {
        Object v = Json.parse(" {\"a\":1,\"b\":-2.5e1,\"c\":\"x\\n\\u00e9\\\"\",\"d\":[true,false,null],\"e\":{}} ");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertEquals(1L, m.get("a"));
        assertEquals(-25.0, m.get("b"));
        assertEquals("x\né\"", m.get("c"));
        assertEquals(Arrays.asList(true, false, null), m.get("d"));
        assertEquals(Map.of(), m.get("e"));
    }

    @Test
    void stringifyEscapesForScriptSafety() {
        assertEquals("\"\\u003c/script\\u003e \\u0026 \\\"q\\\" \\n\"", Json.stringify("</script> & \"q\" \n"));
    }

    @Test
    void roundTrip() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", List.of(1L, 2.5, "s"));
        m.put("nested", Map.of("k", true));
        m.put("nil", null);
        assertEquals(m, Json.parse(Json.stringify(m)));
    }

    @Test
    void writesIntegralDoublesWithoutFraction() {
        assertEquals("[3,3.5,null]", Json.stringify(new double[]{3.0, 3.5, Double.NaN}));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("1 2"));
    }
}
