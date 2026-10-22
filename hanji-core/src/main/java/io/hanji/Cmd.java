package io.hanji;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds bridge command maps. */
final class Cmd {

    private Cmd() {
    }

    static Map<String, Object> of(String op, Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
