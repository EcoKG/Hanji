package io.hanji.examples.monitor;

import io.hanji.Hanji;
import io.hanji.HanjiApplication;
import io.hanji.Tray;
import io.hanji.Window;
import io.hanji.menu.Menu;
import io.hanji.menu.MenuItem;

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

        Tray tray = Tray.create()
                .setTooltip("JVM Monitor")
                .setOnAction(window::toFront)
                .setMenu(new Menu(null,
                        new MenuItem("창 보기", window::toFront),
                        new MenuItem("GC 실행", System::gc),
                        MenuItem.separator(),
                        new MenuItem("종료", Hanji::exit)));

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
                tray.setTooltip(String.format("JVM Monitor - heap %.0f MB", sample.heapUsed() / 1048576.0));
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
