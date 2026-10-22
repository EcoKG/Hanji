package io.hanji;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Preconditions of the browser tests: skipped locally when unmet, but failing when HANJI_REQUIRE_E2E is set (CI). */
final class E2E {

    private E2E() {
    }

    static boolean required() {
        String v = System.getenv("HANJI_REQUIRE_E2E");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    static void require(boolean condition, String message) {
        if (required()) {
            assertTrue(condition, message + " (HANJI_REQUIRE_E2E is set, so this test must run)");
        } else {
            assumeTrue(condition, message);
        }
    }
}
