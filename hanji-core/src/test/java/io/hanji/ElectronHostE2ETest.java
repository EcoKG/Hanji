package io.hanji;

import io.hanji.electron.ElectronHost;
import io.hanji.host.Displays;
import io.hanji.menu.Menu;
import io.hanji.menu.MenuBar;
import io.hanji.menu.MenuItem;
import io.hanji.menu.MenuRole;
import io.hanji.ui.Button;
import io.hanji.ui.Label;
import io.hanji.ui.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the {@code electron} host for real: native window controls, menus, tray, close requests, many windows over
 * IPC and the final exit. Electron is downloaded into the cache on first use. Needs a display (on Linux CI
 * {@code xvfb-run} plus a window manager for the maximize/minimize checks).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElectronHostE2ETest {

    static volatile int devToolsPort;

    public static final class App extends HanjiApplication {
        static final CompletableFuture<Window> SHOWN = new CompletableFuture<>();
        static final List<Throwable> ERRORS = new CopyOnWriteArrayList<>();
        static Label label;
        static MenuItem doItem;
        static MenuItem flagItem;

        @Override
        public void configure(HanjiConfig config) {
            config.setHost("electron").setAppName("HanjiE2E").setErrorHandler(t -> {
                ERRORS.add(t);
                t.printStackTrace();
            });
            config.getElectronArgs().add("--remote-debugging-port=" + devToolsPort);
        }

        @Override
        public void start(Window window) {
            label = new Label("electron");
            Button button = new Button("press", e -> label.setText("clicked"));
            doItem = new MenuItem("Do", "CmdOrCtrl+D", () -> label.setText("menu"));
            flagItem = MenuItem.checkbox("Flag", false, on -> label.setText("flag=" + on));
            window.setTitle("Electron E2E").setSize(640, 420).setPosition(20, 20)
                    .setMenuBar(new MenuBar(new Menu("File", doItem, flagItem, MenuItem.separator(),
                            MenuItem.role(MenuRole.QUIT)), Menu.standard(MenuRole.EDIT_MENU)))
                    .setContent(new VBox(label, button))
                    .show();
            SHOWN.complete(window);
        }
    }

    private Thread appThread;
    private Window window;
    private Cdp page;

    @BeforeAll
    void start() throws Exception {
        E2E.require(Displays.hasDisplay(), "The electron host needs a display (use xvfb-run on Linux)");
        try (ServerSocket s = new ServerSocket(0)) {
            devToolsPort = s.getLocalPort();
        }
        appThread = new Thread(() -> Hanji.launch(App.class), "electron-e2e");
        appThread.start();
        // the first run may download Electron (~120 MB)
        window = App.SHOWN.get(300, TimeUnit.SECONDS);
        page = new Cdp(Cdp.targetUrl(devToolsPort, "page", Window.INLINE_PAGE));
    }

    @AfterAll
    void stop() throws Exception {
        if (page != null) {
            page.close();
        }
        if (appThread != null && appThread.isAlive()) {
            Hanji.exit();
            appThread.join(20_000);
        }
    }

    @Test
    @Order(1)
    void pagesTalkToJavaOverIpc() throws Exception {
        page.waitFor("document.querySelector('.hj-label')?.textContent === 'electron'");
        assertEquals("ipc", page.eval("hanji.__stats().transport"));
        page.eval("document.querySelector('.hj-button').click()");
        page.waitFor("document.querySelector('.hj-label').textContent === 'clicked'");
        assertEquals("Electron E2E", page.eval("document.title"));
    }

    @Test
    @Order(2)
    void boundsChangeAtRuntime() throws Exception {
        onUi(() -> window.setBounds(new Bounds(40, 50, 700, 480)));
        waitUntil(() -> call(() -> window.getWidth() == 700 && window.getHeight() == 480), "size 700x480");
        waitUntil(() -> call(() -> window.getX() == 40 && window.getY() == 50), "position 40,50");
        page.waitFor("window.innerWidth > 0 && window.innerWidth <= 700");
    }

    @Test
    @Order(3)
    void windowStates() throws Exception {
        onUi(window::maximize);
        waitUntil(() -> call(() -> window.getState() == WindowState.MAXIMIZED), "maximized");
        onUi(window::restore);
        waitUntil(() -> call(() -> window.getState() == WindowState.NORMAL), "restored from maximized");
        onUi(window::minimize);
        waitUntil(() -> call(() -> window.getState() == WindowState.MINIMIZED), "minimized");
        onUi(window::restore);
        waitUntil(() -> call(() -> window.getState() == WindowState.NORMAL), "restored from minimized");
    }

    @Test
    @Order(4)
    void nativeMenuItemsReachJava() throws Exception {
        clickMenu(App.doItem);
        page.waitFor("document.querySelector('.hj-label').textContent === 'menu'");
        clickMenu(App.flagItem);
        page.waitFor("document.querySelector('.hj-label').textContent === 'flag=true'");
        assertTrue(onUi(App.flagItem::isChecked));

        // disabled items do nothing; the change is pushed to the native menu
        onUi(() -> App.doItem.setEnabled(false));
        Thread.sleep(300);
        onUi(() -> App.label.setText("before"));
        clickMenu(App.doItem);
        Thread.sleep(500);
        assertEquals("before", page.eval("document.querySelector('.hj-label').textContent"));
    }

    @Test
    @Order(5)
    void trayIconAndItsMenu() throws Exception {
        CompletableFuture<String> clicked = new CompletableFuture<>();
        CompletableFuture<String> chosen = new CompletableFuture<>();
        MenuItem show = new MenuItem("Show", () -> chosen.complete("show"));
        Tray tray = onUi(() -> Tray.create().setTooltip("Hanji").setMenu(new Menu(null, show))
                .setOnAction(() -> clicked.complete("click")));
        assertTrue(onUi(tray::isSupported));
        Thread.sleep(300);
        host().sendTestCommand(Map.of("action", "click-tray"));
        assertEquals("click", clicked.get(10, TimeUnit.SECONDS));
        clickMenu(show);
        assertEquals("show", chosen.get(10, TimeUnit.SECONDS));
        onUi(() -> {
            tray.remove();
            return null;
        });
    }

    @Test
    @Order(6)
    void manyWindowsAtOnce() throws Exception {
        // more windows than a browser's 6 connections per host: every page must stay connected
        List<Window> windows = new ArrayList<>();
        AtomicInteger ready = new AtomicInteger();
        onUi(() -> {
            for (int i = 0; i < 8; i++) {
                Window w = new Window().setTitle("W" + i).setSize(260, 160).setPosition(20 + i * 30, 500);
                w.setContent(new Label("window " + i));
                w.onReady(ready::incrementAndGet);
                w.show();
                windows.add(w);
            }
            return null;
        });
        waitUntil(() -> ready.get() == 8, "8 windows ready");
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i);
            assertEquals("window " + i, w.evalSync("document.body.textContent"));
        }
        onUi(() -> {
            windows.forEach(Window::close);
            return null;
        });
    }

    @Test
    @Order(7)
    void closeRequestsCanBeCancelled() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Window second = onUi(() -> {
            Window w = new Window().setTitle("Closable").setSize(300, 200);
            w.setContent(new Label("closable"));
            w.setOnCloseRequest(request -> {
                if (requests.incrementAndGet() == 1) {
                    request.cancel();
                }
            });
            return w.show();
        });
        waitUntil(() -> call(second::isPageReady), "second window ready");

        String url = onUi(() -> second.getUrl().toString());
        host().sendTestCommand(Map.of("action", "user-close", "url", url));
        waitUntil(() -> requests.get() == 1, "first close request");
        Thread.sleep(500);
        assertTrue(onUi(second::isShowing), "the cancelled request must keep the window open");

        host().sendTestCommand(Map.of("action", "user-close", "url", url));
        waitUntil(() -> call(second::isClosed), "window closed after an accepted request");
        assertEquals(2, requests.get());
    }

    @Test
    @Order(8)
    void closingTheLastWindowEndsTheApplication() throws Exception {
        onUi(() -> {
            window.close();
            return null;
        });
        appThread.join(20_000);
        assertFalse(appThread.isAlive(), "Hanji.launch should return after the last window closed");
        assertEquals(List.of(), App.ERRORS);
    }

    // ------------------------------------------------------------------ helpers

    private void clickMenu(MenuItem item) {
        host().sendTestCommand(Map.of("action", "click-menu", "id", item.getId()));
    }

    private static ElectronHost host() {
        return (ElectronHost) HanjiRuntime.get().host();
    }

    private static <T> T onUi(Callable<T> task) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Hanji.runLater(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(15, TimeUnit.SECONDS);
    }

    private static void onUi(Runnable task) throws Exception {
        onUi(() -> {
            task.run();
            return null;
        });
    }

    private static boolean call(Callable<Boolean> check) {
        try {
            return onUi(check);
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
