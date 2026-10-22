package io.hanji.host;

import io.hanji.HanjiConfig;
import io.hanji.spi.HostCallbacks;
import io.hanji.spi.HostWindow;
import io.hanji.spi.HostWindowSpec;
import io.hanji.spi.WindowHost;
import io.hanji.spi.WindowHostProvider;

/**
 * Opens nothing; prints the URL so any browser (or a test driver) can open it.
 * Used automatically when no display is available (servers, CI).
 */
public final class ExternalHost implements WindowHost {

    @Override
    public String name() {
        return "external";
    }

    @Override
    public HostWindow open(HostWindowSpec spec, HostCallbacks callbacks) {
        System.out.println("[hanji] Open \"" + spec.title() + "\" in a browser: " + spec.url());
        return () -> { };
    }

    /** Provider registration. */
    public static final class Provider implements WindowHostProvider {
        @Override
        public String name() {
            return "external";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean isAvailable(HanjiConfig config) {
            return true;
        }

        @Override
        public WindowHost create(HanjiConfig config) {
            return new ExternalHost();
        }
    }
}
