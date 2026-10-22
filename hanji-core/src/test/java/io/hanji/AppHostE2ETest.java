package io.hanji;

import io.hanji.host.ChromiumLocator;
import io.hanji.host.Displays;
import io.hanji.ui.Button;
import io.hanji.ui.Label;
import io.hanji.ui.VBox;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Runs the real {@code app} host on the current OS: locates Chrome/Edge/Chromium, opens an app-mode window,
 * drives it over DevTools, closes the browser and expects the application to exit (implicit exit).
 * Needs a display (on Linux CI: {@code xvfb-run}); skipped otherwise.
 */
class AppHostE2ETest {

    static volatile int devToolsPort;

    public static final class App extends HanjiApplication {
        static final CompletableFuture<Window> SHOWN = new CompletableFuture<>();
        static final List<Throwable> ERRORS = new CopyOnWriteArrayList<>();

        @Override
        public void configure(HanjiConfig config) {
            config.setHost("app").setCloseGraceMillis(1000).setErrorHandler(t -> {
                ERRORS.add(t);
                t.printStackTrace();
            });
            config.getBrowserArgs().addAll(List.of("--remote-debugging-port=" + devToolsPort,
                    "--no-sandbox", "--disable-gpu"));
        }

        @Override
        public void start(Window window) {
            Label label = new Label("app host");
            Button button = new Button("press", e -> label.setText("clicked"));
            window.setTitle("App host").setSize(640, 400).setContent(new VBox(label, button)).show();
            SHOWN.complete(window);
        }
    }

    @Test
    void opensAppWindowAndExitsWhenTheUserClosesIt() throws Exception {
        Path browser = ChromiumLocator.find(System.getenv("HANJI_BROWSER"));
        E2E.require(browser != null, "No Chromium-based browser found");
        E2E.require(Displays.hasDisplay(), "The app host needs a display (use xvfb-run on Linux)");
        try (ServerSocket s = new ServerSocket(0)) {
            devToolsPort = s.getLocalPort();
        }

        Thread appThread = new Thread(() -> Hanji.launch(App.class), "app-host-test");
        appThread.start();
        App.SHOWN.get(20, TimeUnit.SECONDS);

        try (Cdp page = new Cdp(Cdp.targetUrl(devToolsPort, "page", Window.INLINE_PAGE))) {
            page.waitFor("document.querySelector('.hj-label')?.textContent === 'app host'");
            page.eval("document.querySelector('.hj-button').click()");
            page.waitFor("document.querySelector('.hj-label').textContent === 'clicked'");
            assertEquals("App host", page.eval("document.title"));
        }
        // Simulates the user quitting the window: the browser process ends -> window closed -> app exits
        try (Cdp browserSession = new Cdp(Cdp.targetUrl(devToolsPort, "browser", ""))) {
            browserSession.fire("Browser.close");
            appThread.join(20_000);
        }
        assertFalse(appThread.isAlive(), "Hanji.launch should return after the last window was closed");
        assertEquals(List.of(), App.ERRORS);
    }
}
