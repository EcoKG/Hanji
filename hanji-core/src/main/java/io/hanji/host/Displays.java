package io.hanji.host;

import java.util.Locale;

/** Platform helpers for hosts. */
public final class Displays {

    private Displays() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    /** Whether a graphical desktop is available (on Linux/Unix: DISPLAY or WAYLAND_DISPLAY is set). */
    public static boolean hasDisplay() {
        if (Boolean.getBoolean("java.awt.headless")) {
            return false;
        }
        if (isWindows() || isMac()) {
            return true;
        }
        return notBlank(System.getenv("DISPLAY")) || notBlank(System.getenv("WAYLAND_DISPLAY"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
