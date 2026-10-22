package io.hanji.examples.showcase;

import io.hanji.Bounds;
import io.hanji.Hanji;
import io.hanji.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Clicks through the showcase and takes screenshots ({@code -Dshowcase.tour=<dir>}). The screenshot commands can be
 * changed with {@code -Dshowcase.shot="..."} (whole screen) and {@code -Dshowcase.shotWindow="..."} (window area);
 * placeholders: {@code {file} {x} {y} {w} {h}}. Defaults: {@code screencapture} on macOS, ImageMagick elsewhere.
 * The tour drives the controllers the same way the UI does, through the UI thread.
 */
final class Tour implements Runnable {

    private final Window window;
    private final Store store;
    private final Path dir;
    private final String shot;
    private final String shotWindow;
    private boolean firstShot = true;

    private Tour(Window window, Store store, Path dir) {
        this.window = window;
        this.store = store;
        this.dir = dir;
        boolean mac = Ui.isMac();
        this.shot = System.getProperty("showcase.shot", mac ? "screencapture -x {file}" : "import -window root {file}");
        this.shotWindow = System.getProperty("showcase.shotWindow", mac ? "screencapture -x -R{x},{y},{w},{h} {file}"
                : "import -window root -crop {w}x{h}+{x}+{y} +repage {file}");
    }

    static void startIfRequested(Window window, Store store) {
        String dir = System.getProperty("showcase.tour");
        if (dir != null) {
            Thread t = new Thread(new Tour(window, store, Path.of(dir)), "showcase-tour");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void run() {
        try {
            Files.createDirectories(dir);
            await(LoginController.class);
            pause(1500);
            shot("01-login", window);

            ui(() -> window.<LoginController>getController().fillDemo());
            pause(400);
            ui(() -> window.<LoginController>getController().submit());
            await(MainController.class);
            pause(2200);
            shot("02-dashboard", window);

            ui(() -> main().navigate("orders"));
            pause(1000);
            shot("03-orders", window);

            ui(() -> main().newOrder(null));
            pause(500);
            ui(() -> main().newOrderModal.fill("하윤서", "yunseo.ha@example.com", 3, 2, "스마트스토어", "선물 포장 부탁드립니다"));
            pause(900);
            shot("04-new-order-modal", window);

            ui(() -> main().newOrderModal.submit());
            pause(1200);
            shot("05-order-created", window);

            ui(() -> main().navigate("customers"));
            pause(1000);
            shot("06-customers", window);

            ui(() -> main().showCustomer(store.customers.stream().filter(c -> store.grade(c) == Customer.Grade.VIP)
                    .max(java.util.Comparator.comparingInt(c -> store.ordersOf(c).size())).orElse(store.customers.get(0))));
            pause(1000);
            shot("07-customer-modal", window);
            ui(() -> main().customerModal.close());

            Order order = store.orders.get(2);
            ui(() -> main().openOrder(order));
            pause(3000);
            shot("08-order-window", call(() -> OrderWindowController.find(order.id)));

            ui(OrderWindowController::closeAll);
            ui(() -> {
                window.toFront();
                main().navigate("settings");
                main().setTheme("dark");
            });
            pause(1500);
            shot("09-settings-dark", window);

            ui(() -> main().navigate("dashboard"));
            pause(1500);
            shot("10-dashboard-dark", window);
            ui(() -> main().setTheme("system"));
            log("done: " + dir.toAbsolutePath());
        } catch (Throwable t) {
            log("failed: " + t);
            t.printStackTrace();
        } finally {
            if (!Boolean.getBoolean("showcase.tour.keepOpen")) {
                Hanji.exit();
            }
        }
    }

    private MainController main() {
        return window.getController();
    }

    private void await(Class<?> controllerType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!call(() -> window.isPageReady() && controllerType.isInstance(window.getController()))) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Timed out waiting for " + controllerType.getSimpleName());
            }
            pause(200);
        }
    }

    private void shot(String name, Window target) throws Exception {
        awaitRendered(target);
        Bounds b = call(target::getBounds);
        exec(shot, dir.resolve(name + ".png"), b);
        if (b != null && !shotWindow.isBlank()) {
            exec(shotWindow, dir.resolve(name + "-window.png"), b);
        }
    }

    /**
     * Waits until the page is visible and produces animation frames, plus time for rasterization. On slow machines
     * (CI virtual machines render in software) the first frames of a new window can take several seconds.
     */
    private void awaitRendered(Window target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String script = "new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(document.visibilityState))))";
        while (true) {
            CompletableFuture<Object> frames = call(() -> target.eval(script));
            try {
                Object state = frames.get(3, TimeUnit.SECONDS);
                if ("visible".equals(state)) {
                    break;
                }
                log("page is " + state + ", waiting");
            } catch (TimeoutException e) {
                log("no animation frame yet, waiting");
            }
            if (System.nanoTime() > deadline) {
                log("page did not render in time; taking the screenshot anyway");
                break;
            }
            pause(500);
        }
        pause(firstShot ? 3000 : 600);
        firstShot = false;
    }

    private void exec(String template, Path file, Bounds b) throws Exception {
        List<String> command = new ArrayList<>();
        for (String part : template.trim().split("\\s+")) {
            command.add(part.replace("{file}", file.toAbsolutePath().toString())
                    .replace("{x}", String.valueOf(b == null ? 0 : b.x()))
                    .replace("{y}", String.valueOf(b == null ? 0 : b.y()))
                    .replace("{w}", String.valueOf(b == null ? 0 : b.width()))
                    .replace("{h}", String.valueOf(b == null ? 0 : b.height())));
        }
        Process p = new ProcessBuilder(command).inheritIO().start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        log(String.format(Locale.ROOT, "%s -> %s", file.getFileName(), finished ? "exit " + p.exitValue() : "timeout"));
    }

    private static void ui(Runnable action) throws Exception {
        call(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T call(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Hanji.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(20, TimeUnit.SECONDS);
    }

    private static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static void log(String message) {
        System.out.println("[tour] " + message);
    }
}
