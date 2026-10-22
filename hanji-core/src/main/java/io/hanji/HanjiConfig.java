package io.hanji;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Application settings. Defaults can be overridden with system properties ({@code -Dhanji.xxx=...})
 * or in {@link HanjiApplication#configure(HanjiConfig)}.
 *
 * <table>
 *   <caption>System properties</caption>
 *   <tr><td>hanji.host</td><td>auto | app | browser | external</td></tr>
 *   <tr><td>hanji.webRoot</td><td>classpath folder with the pages (default {@code web})</td></tr>
 *   <tr><td>hanji.port</td><td>fixed server port (default 0 = random)</td></tr>
 *   <tr><td>hanji.dev</td><td>true: serve pages from the source folder, allow reload (F5)</td></tr>
 *   <tr><td>hanji.devResources</td><td>source resource folder for dev mode (default {@code src/main/resources})</td></tr>
 *   <tr><td>hanji.browser</td><td>Chrome/Edge/Chromium executable for the {@code app} host (env: HANJI_BROWSER)</td></tr>
 *   <tr><td>hanji.browserArgs</td><td>extra browser arguments, space separated</td></tr>
 *   <tr><td>hanji.debug</td><td>verbose logging</td></tr>
 * </table>
 */
public final class HanjiConfig {

    private String host = "auto";
    private String webRoot = "web";
    private int port;
    private boolean devMode;
    private Path devResourceDir;
    private boolean implicitExit = true;
    private long closeGraceMillis = 2500;
    private String browserPath;
    private final List<String> browserArgs = new ArrayList<>();
    private Locale locale = Locale.getDefault();
    private Consumer<Throwable> errorHandler = HanjiConfig::printError;

    public static HanjiConfig fromSystemProperties() {
        HanjiConfig c = new HanjiConfig();
        c.host = System.getProperty("hanji.host", c.host);
        c.webRoot = System.getProperty("hanji.webRoot", c.webRoot);
        c.port = Integer.getInteger("hanji.port", 0);
        c.devMode = Boolean.getBoolean("hanji.dev");
        String devDir = System.getProperty("hanji.devResources");
        if (devDir != null && !devDir.isBlank()) {
            c.devResourceDir = Path.of(devDir);
        }
        String browser = System.getProperty("hanji.browser", System.getenv("HANJI_BROWSER"));
        if (browser != null && !browser.isBlank()) {
            c.browserPath = browser;
        }
        String args = System.getProperty("hanji.browserArgs");
        if (args != null && !args.isBlank()) {
            c.browserArgs.addAll(Arrays.asList(args.trim().split("\\s+")));
        }
        c.closeGraceMillis = Long.getLong("hanji.closeGrace", c.closeGraceMillis);
        return c;
    }

    private static void printError(Throwable t) {
        System.err.println("[hanji] Uncaught exception on the UI thread:");
        t.printStackTrace();
    }

    public String getHost() {
        return host;
    }

    /** Window host: {@code auto} (default), {@code app}, {@code browser} or {@code external}. */
    public HanjiConfig setHost(String host) {
        this.host = Objects.requireNonNull(host);
        return this;
    }

    public String getWebRoot() {
        return webRoot;
    }

    /** Classpath folder that holds pages and assets (default {@code web}). */
    public HanjiConfig setWebRoot(String webRoot) {
        String w = Objects.requireNonNull(webRoot).replace('\\', '/');
        while (w.startsWith("/")) {
            w = w.substring(1);
        }
        while (w.endsWith("/")) {
            w = w.substring(0, w.length() - 1);
        }
        this.webRoot = w;
        return this;
    }

    public int getPort() {
        return port;
    }

    public HanjiConfig setPort(int port) {
        this.port = port;
        return this;
    }

    public boolean isDevMode() {
        return devMode;
    }

    /** Dev mode: pages are served from {@link #getDevResourceDir()} (edit + F5 without rebuilding). */
    public HanjiConfig setDevMode(boolean devMode) {
        this.devMode = devMode;
        return this;
    }

    /** Source resource folder used in dev mode; defaults to {@code src/main/resources} if it exists. */
    public Path getDevResourceDir() {
        if (devResourceDir != null) {
            return devResourceDir;
        }
        Path p = Path.of("src", "main", "resources");
        return Files.isDirectory(p) ? p : null;
    }

    public HanjiConfig setDevResourceDir(Path dir) {
        this.devResourceDir = dir;
        return this;
    }

    public boolean isImplicitExit() {
        return implicitExit;
    }

    /** Exit the application when the last window is closed (default true). */
    public HanjiConfig setImplicitExit(boolean implicitExit) {
        this.implicitExit = implicitExit;
        return this;
    }

    public long getCloseGraceMillis() {
        return closeGraceMillis;
    }

    /** How long a page may stay disconnected before its window counts as closed by the user. */
    public HanjiConfig setCloseGraceMillis(long millis) {
        this.closeGraceMillis = millis;
        return this;
    }

    public String getBrowserPath() {
        return browserPath;
    }

    public HanjiConfig setBrowserPath(String browserPath) {
        this.browserPath = browserPath;
        return this;
    }

    public List<String> getBrowserArgs() {
        return browserArgs;
    }

    public Locale getLocale() {
        return locale;
    }

    /** Locale for built-in texts (dialog buttons). */
    public HanjiConfig setLocale(Locale locale) {
        this.locale = Objects.requireNonNull(locale);
        return this;
    }

    public Consumer<Throwable> getErrorHandler() {
        return errorHandler;
    }

    /** Receives exceptions thrown by handlers on the UI thread (default: print to stderr). */
    public HanjiConfig setErrorHandler(Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler);
        return this;
    }
}
