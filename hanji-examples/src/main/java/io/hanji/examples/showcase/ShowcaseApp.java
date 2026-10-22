package io.hanji.examples.showcase;

import io.hanji.HanjiApplication;
import io.hanji.HanjiConfig;
import io.hanji.Window;

/**
 * "Hanji Studio": a multi-page order management app built with HTML/CSS and Java controllers.
 * Login, page navigation (sidebar, native menu, shortcuts), modals, a second window per order, live sync between
 * windows, theme and native window control.
 * <pre>
 * java -jar hanji-showcase.jar
 * mvn -pl hanji-examples exec:java -Dexample=showcase
 * </pre>
 * Add {@code -Dshowcase.tour=<dir>} to click through all screens automatically and save screenshots there.
 */
public class ShowcaseApp extends HanjiApplication {

    private final Store store = new Store();

    @Override
    public void configure(HanjiConfig config) {
        config.setAppName("Hanji Studio");
    }

    @Override
    public void start(Window window) {
        window.setTitle("Hanji Studio")
                .setSize(1360, 860)
                .setMinimumSize(960, 640)
                .center()
                .load("showcase/login.html", new LoginController(store))
                .show();
        window.onClosed(OrderWindowController::closeAll);
        Tour.startIfRequested(window, store);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
