package io.hanji.host;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds a Chromium-based browser (Chrome, Edge, Chromium, Brave) for app-mode windows. */
public final class ChromiumLocator {

    private ChromiumLocator() {
    }

    /** {@code explicitPath} (if usable), otherwise the first installed browser, otherwise {@code null}. */
    public static Path find(String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path p = Path.of(explicitPath);
            if (Files.isExecutable(p)) {
                return p;
            }
            Path onPath = searchPath(explicitPath);
            if (onPath != null) {
                return onPath;
            }
        }
        if (!Displays.isWindows() && !Displays.isMac()) {
            // Linux/BSD: the user's PATH first (respects their preferred installation) ...
            for (String name : LINUX_NAMES) {
                Path p = searchPath(name);
                if (p != null) {
                    return p;
                }
            }
        }
        // ... then well-known locations (also covers IDEs started with a reduced PATH, Snap and Flatpak)
        for (Path candidate : candidates()) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static final String[] LINUX_NAMES = {"google-chrome", "google-chrome-stable", "chromium",
            "chromium-browser", "microsoft-edge", "microsoft-edge-stable", "brave-browser", "vivaldi"};

    private static List<Path> candidates() {
        List<Path> list = new ArrayList<>();
        if (Displays.isWindows()) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String base = System.getenv(env);
                if (base == null) {
                    continue;
                }
                list.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
                list.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
                list.add(Path.of(base, "Chromium", "Application", "chrome.exe"));
                list.add(Path.of(base, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
            }
        } else if (Displays.isMac()) {
            for (String root : new String[]{"/Applications", System.getProperty("user.home") + "/Applications"}) {
                list.add(Path.of(root, "Google Chrome.app", "Contents", "MacOS", "Google Chrome"));
                list.add(Path.of(root, "Microsoft Edge.app", "Contents", "MacOS", "Microsoft Edge"));
                list.add(Path.of(root, "Chromium.app", "Contents", "MacOS", "Chromium"));
                list.add(Path.of(root, "Brave Browser.app", "Contents", "MacOS", "Brave Browser"));
            }
        } else {
            for (String dir : new String[]{"/usr/bin", "/usr/local/bin"}) {
                for (String name : LINUX_NAMES) {
                    list.add(Path.of(dir, name));
                }
            }
            list.add(Path.of("/opt/google/chrome/google-chrome"));
            list.add(Path.of("/opt/microsoft/msedge/microsoft-edge"));
            list.add(Path.of("/opt/brave.com/brave/brave-browser"));
            // Snap (Ubuntu ships Chromium only as a snap; Fedora/Arch mount snaps under /var/lib/snapd)
            list.add(Path.of("/snap/bin/chromium"));
            list.add(Path.of("/var/lib/snapd/snap/bin/chromium"));
            // Flatpak exports (system-wide and per-user installations)
            String home = System.getProperty("user.home");
            for (String root : new String[]{"/var/lib/flatpak/exports/bin", home + "/.local/share/flatpak/exports/bin"}) {
                for (String app : new String[]{"com.google.Chrome", "com.microsoft.Edge", "org.chromium.Chromium",
                        "com.brave.Browser", "io.github.ungoogled_software.ungoogled_chromium"}) {
                    list.add(Path.of(root, app));
                }
            }
        }
        return list;
    }

    private static Path searchPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }
}
