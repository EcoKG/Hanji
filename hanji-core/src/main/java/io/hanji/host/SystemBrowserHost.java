package io.hanji.host;

import io.hanji.HanjiConfig;
import io.hanji.spi.HostCallbacks;
import io.hanji.spi.HostWindow;
import io.hanji.spi.HostWindowSpec;
import io.hanji.spi.WindowHost;
import io.hanji.spi.WindowHostProvider;

import java.io.IOException;
import java.net.URI;

/** Opens windows as tabs of the default browser. Closing the tab closes the Hanji window. */
public final class SystemBrowserHost implements WindowHost {

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public HostWindow open(HostWindowSpec spec, HostCallbacks callbacks) throws IOException {
        browse(spec.url());
        return () -> { };
    }

    /** Opens {@code uri} with the operating system's default browser (no AWT/Swing involved). */
    static void browse(URI uri) throws IOException {
        String[] cmd;
        if (Displays.isWindows()) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", uri.toString()};
        } else if (Displays.isMac()) {
            cmd = new String[]{"open", uri.toString()};
        } else {
            // $BROWSER is the Unix convention for the user's preferred browser; xdg-open asks the desktop
            // ("cmd1:cmd2", each optionally with a %s placeholder for the URL)
            String browser = System.getenv("BROWSER");
            String first = browser == null ? "" : browser.split(":")[0].trim();
            if (first.isEmpty()) {
                cmd = new String[]{"xdg-open", uri.toString()};
            } else if (first.contains("%s")) {
                cmd = first.replace("%s", uri.toString()).split("\\s+");
            } else {
                cmd = (first + " " + uri).split("\\s+");
            }
        }
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Provider registration. */
    public static final class Provider implements WindowHostProvider {
        @Override
        public String name() {
            return "browser";
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public boolean isAvailable(HanjiConfig config) {
            return Displays.hasDisplay();
        }

        @Override
        public WindowHost create(HanjiConfig config) {
            return new SystemBrowserHost();
        }
    }
}
