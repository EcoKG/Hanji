package io.hanji.examples.monitor;

import io.hanji.Hanji;
import io.hanji.HanjiApplication;
import io.hanji.Window;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A JavaScript-heavy page (canvas chart) fed by Java: a background thread samples the JVM every second
 * and publishes the data to the page. Run: mvn -pl hanji-examples exec:java -Dexample=monitor
 */
public class MonitorApp extends HanjiApplication {

    private ScheduledExecutorService sampler;

    @Override
    public void start(Window window) {
        window.setTitle("JVM Monitor").setSize(980, 680)
                .load("monitor/index.html", new MonitorController())
                .show();

        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jvm-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(() -> {
            Sample sample = Sample.capture();                 // background thread
            Hanji.runLater(() -> {                            // UI thread
                if (window.isPageReady()) {
                    window.publish("sample", sample);
                }
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
