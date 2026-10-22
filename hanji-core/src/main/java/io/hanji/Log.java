package io.hanji;

/** Tiny logger writing to stderr, so the core needs no logging dependency. */
final class Log {

    private static volatile boolean debug = Boolean.getBoolean("hanji.debug");

    private Log() {
    }

    static void setDebug(boolean enabled) {
        debug = enabled;
    }

    static void debug(String message) {
        if (debug) {
            System.err.println("[hanji] " + message);
        }
    }

    static void info(String message) {
        System.err.println("[hanji] " + message);
    }

    static void warn(String message) {
        System.err.println("[hanji] WARNING: " + message);
    }
}
