package io.hanji;

import io.hanji.host.ChromiumLocator;
import io.hanji.json.Json;
import io.hanji.ui.Button;
import io.hanji.ui.CheckBox;
import io.hanji.ui.HBox;
import io.hanji.ui.Label;
import io.hanji.ui.ListView;
import io.hanji.ui.TableView;
import io.hanji.ui.TextField;
import io.hanji.ui.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: runs a Hanji application with the {@code external} host and drives the pages with a
 * headless Chromium over the DevTools protocol. Skipped when no Chromium-based browser is installed
 * (set HANJI_BROWSER to point at one).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrowserE2ETest {

    record Person(String name, int age) {
    }

    record FormData(String title, int count) {
    }

    /** Controller for e2e.html. */
    public static final class E2EController {
        static final List<E2EController> INSTANCES = new CopyOnWriteArrayList<>();

        @UiField Element name;
        @UiField Element out;
        @UiField Element items;
        @UiField ListView<String> fruits;
        @UiField Element selected;
        @UiField TextField mirror;
        @UiField Element mirrorOut;
        @UiField CheckBox agree;
        @UiField Element formOut;
        @UiField Window window;
        @UiField Element syncOut;
        @UiField Element keptList;
        @UiField Element staticList;
        @UiField Element staticItem;
        Element kept;
        int added;

        E2EController() {
            INSTANCES.add(this);
        }

        void initialize() {
            out.setText("ready");
            fruits.getItems().setAll("apple", "banana", "cherry");
            selected.bindText(fruits.selectedItemProperty());
            mirrorOut.bindText(mirror.textProperty());
        }

        @UiHandler("greet")
        void greet() {
            out.setText("Hello, " + name.getValue() + (agree.isSelected() ? "!" : ""));
        }

        @UiHandler("add")
        void add() {
            added++;
            items.append(Element.create("li").addClass("item").setText("item " + added));
        }

        @UiHandler(value = "form", event = "submit")
        void submit(Event e) {
            FormData f = e.getForm(FormData.class);
            formOut.setText(f.title() + "/" + (f.count() * 2));
        }

        /** Synchronous reads inside an event handler (nested event loop, no deadlock). */
        @UiHandler("syncBtn")
        void syncRead() {
            syncOut.setText("sync:" + out.getText() + ":" + name.getAttribute("id"));
        }

        /** A Java-created element survives clear() of its parent and can be appended again. */
        @UiHandler("reuseBtn")
        void reuse() {
            if (kept == null) {
                kept = Element.create("li").setText("kept");
            }
            keptList.clear();
            keptList.append(kept);
        }

        /** The same for an element of the HTML page that Java references. */
        @UiHandler("reuseStatic")
        void reuseStatic() {
            staticList.clear();
            staticList.append(staticItem);
        }

        @JsCallable
        int sum(int a, int b) {
            return a + b;
        }

        @JsCallable
        Person older(Person p) {
            return new Person(p.name(), p.age() + 1);
        }

        @JsCallable(background = true)
        String where() {
            return Hanji.isUiThread() ? "ui" : "worker";
        }

        @JsCallable
        void fail() {
            throw new IllegalStateException("boom");
        }
    }

    public static final class TestApp extends HanjiApplication {
        static volatile CompletableFuture<Window> primary;
        static final List<Throwable> ERRORS = new CopyOnWriteArrayList<>();

        @Override
        public void configure(HanjiConfig config) {
            config.setHost("external").setImplicitExit(false).setCloseGraceMillis(600).setErrorHandler(t -> {
                ERRORS.add(t);
                t.printStackTrace();
            });
        }

        @Override
        public void start(Window window) {
            window.setTitle("E2E").load("e2e.html", E2EController::new).show();
            primary.complete(window);
        }
    }

    private Path browser;
    private Path profile;
    private Process chrome;
    private Cdp cdp;
    private Thread appThread;
    private Window window;

    @BeforeAll
    void startAll() throws Exception {
        String env = System.getenv("HANJI_BROWSER");
        browser = ChromiumLocator.find(env);
        E2E.require(browser != null, "No Chromium-based browser found; set HANJI_BROWSER to run the E2E test");

        TestApp.primary = new CompletableFuture<>();
        appThread = new Thread(() -> Hanji.launch(TestApp.class), "e2e-app");
        appThread.start();
        window = TestApp.primary.get(15, TimeUnit.SECONDS);

        profile = Files.createTempDirectory("hanji-e2e-");
        chrome = new ProcessBuilder(browser.toString(), "--headless=new", "--no-sandbox", "--disable-gpu",
                "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
                "--disable-component-update", "--disable-sync", "--remote-debugging-port=0",
                "--user-data-dir=" + profile, "about:blank")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        cdp = new Cdp(pageWebSocketUrl());
        cdp.send("Page.navigate", Map.of("url", onUi(() -> window.getUrl().toString())));
    }

    @AfterAll
    void stopAll() throws Exception {
        if (cdp != null) {
            cdp.close();
        }
        if (chrome != null) {
            chrome.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        if (appThread != null) {
            Hanji.exit();
            appThread.join(10_000);
            assertFalse(appThread.isAlive(), "Hanji.launch did not return after exit()");
        }
        if (profile != null) {
            deleteQuietly(profile);
        }
    }

    @Test
    @Order(1)
    void controllerIsInitializedAndPageScriptsSeeTheApi() throws Exception {
        cdp.waitFor("document.getElementById('out').textContent === 'ready'");
        assertEquals("function", cdp.eval("document.getElementById('early').textContent"));
        assertEquals("E2E", cdp.eval("document.title"));
    }

    @Test
    @Order(2)
    void handlersReadMirroredFormState() throws Exception {
        cdp.eval("(() => { const n = document.getElementById('name'); n.value = 'Kim';"
                + " n.dispatchEvent(new Event('input', {bubbles: true}));"
                + " document.getElementById('agree').click();"
                + " document.getElementById('greet').click(); })()");
        cdp.waitFor("document.getElementById('out').textContent === 'Hello, Kim!'");
    }

    @Test
    @Order(3)
    void createsElementsFromJava() throws Exception {
        cdp.eval("for (let i = 0; i < 3; i++) document.getElementById('add').click()");
        cdp.waitFor("document.querySelectorAll('#items li.item').length === 3");
        assertEquals("item 3", cdp.eval("document.querySelector('#items li:last-child').textContent"));
    }

    @Test
    @Order(4)
    void componentsWrapPageElements() throws Exception {
        cdp.waitFor("document.querySelectorAll('#fruits li').length === 3");
        cdp.eval("document.querySelector('#fruits li[data-index=\"1\"]').click()");
        cdp.waitFor("document.getElementById('selected').textContent === 'banana'");
        cdp.waitFor("document.querySelector('#fruits li[data-index=\"1\"]').classList.contains('hj-selected')");

        cdp.eval("(() => { const m = document.getElementById('mirror'); m.value = 'typed';"
                + " m.dispatchEvent(new Event('input', {bubbles: true})); })()");
        cdp.waitFor("document.getElementById('mirrorOut').textContent === 'typed'");
        onUi(() -> E2EController.INSTANCES.get(0).mirror.setText("from java"));
        cdp.waitFor("document.getElementById('mirror').value === 'from java'");
        cdp.waitFor("document.getElementById('mirrorOut').textContent === 'from java'");
    }

    @Test
    @Order(5)
    void formSubmitIsConvertedToRecord() throws Exception {
        cdp.eval("document.getElementById('submit').click()");
        cdp.waitFor("document.getElementById('formOut').textContent === 'hello/6'");
        assertTrue(String.valueOf(cdp.eval("location.href")).endsWith("e2e.html"), "form must not navigate");
    }

    @Test
    @Order(6)
    void javascriptCallsJava() throws Exception {
        assertEquals(5L, cdp.eval("hanji.call('sum', 2, 3)"));
        assertEquals(Map.of("name", "Lee", "age", 31L), cdp.eval("hanji.call('older', {name: 'Lee', age: 30})"));
        assertEquals("worker", cdp.eval("hanji.call('where')"));
        assertEquals("ERR:boom", cdp.eval("hanji.call('fail').catch(e => 'ERR:' + e.message)"));
        assertTrue(String.valueOf(cdp.eval("hanji.call('nope').catch(e => e.message)")).contains("No Java function"));
        TestApp.ERRORS.removeIf(t -> "boom".equals(t.getMessage()));
    }

    @Test
    @Order(7)
    void javaCallsJavaScript() throws Exception {
        assertEquals(3L, onUi(() -> window.eval("1 + 2")).get(10, TimeUnit.SECONDS));
        assertEquals("ready?", onUi(() -> window.byId("out").eval("el.textContent.replace('Hello, Kim!', 'ready') + '?'"))
                .get(10, TimeUnit.SECONDS));
        assertEquals(3, onUi(() -> window.query("#fruits li")).get(10, TimeUnit.SECONDS).size());
        onUi(() -> {
            window.call("app.show", "x", Map.of("k", "v"));
            window.publish("news", Map.of("text", "breaking"));
            return null;
        });
        cdp.waitFor("document.getElementById('called').textContent === 'x:v'");
        cdp.waitFor("document.getElementById('news').textContent === 'breaking'");

        CompletableFuture<?> failed = onUi(() -> window.eval("throw new TypeError('bad')"));
        try {
            failed.get(10, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof JsException);
            assertTrue(e.getCause().getMessage().contains("bad"));
        }
    }

    @Test
    @Order(8)
    void messagesFromThePage() throws Exception {
        CompletableFuture<Person> received = new CompletableFuture<>();
        onUi(() -> window.onMessage("person", Person.class, received::complete));
        cdp.eval("hanji.emit('person', {name: 'Park', age: 40})");
        assertEquals(new Person("Park", 40), received.get(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(9)
    void dialogs() throws Exception {
        CompletableFuture<Boolean> confirm = onUi(() -> window.confirm("Title", "Proceed?"));
        cdp.waitFor("document.querySelector('dialog.hj-dialog[open] .hj-dialog-message')?.textContent === 'Proceed?'");
        cdp.eval("document.querySelector('dialog.hj-dialog .hj-primary').click()");
        assertTrue(confirm.get(10, TimeUnit.SECONDS));

        CompletableFuture<String> prompt = onUi(() -> window.prompt("Name?", "def"));
        cdp.waitFor("document.querySelector('dialog.hj-dialog .hj-dialog-input')?.value === 'def'");
        cdp.eval("(() => { document.querySelector('.hj-dialog-input').value = 'answer';"
                + " document.querySelector('dialog.hj-dialog .hj-primary').click(); })()");
        assertEquals("answer", prompt.get(10, TimeUnit.SECONDS));

        CompletableFuture<Boolean> cancelled = onUi(() -> window.confirm("Cancel me"));
        cdp.waitFor("document.querySelector('dialog.hj-dialog[open]') !== null");
        cdp.eval("document.querySelector('dialog.hj-dialog .hj-button:not(.hj-primary)').click()");
        assertFalse(cancelled.get(10, TimeUnit.SECONDS));
        cdp.waitFor("document.querySelector('dialog.hj-dialog') === null");
    }

    @Test
    @Order(10)
    void reloadCreatesAFreshController() throws Exception {
        int before = E2EController.INSTANCES.size();
        String oldPageId = String.valueOf(cdp.eval("hanji.pageId"));
        cdp.send("Page.reload", Map.of());
        // the old document also says 'ready': wait for the new page instance to be initialized by Java
        cdp.waitFor("window.hanji && hanji.pageId !== '" + oldPageId + "'"
                + " && document.getElementById('out').textContent === 'ready'");
        assertEquals(before + 1, E2EController.INSTANCES.size());
        cdp.eval("(() => { const n = document.getElementById('name'); n.value = 'Choi';"
                + " n.dispatchEvent(new Event('input', {bubbles: true}));"
                + " document.getElementById('greet').click(); })()");
        cdp.waitFor("document.getElementById('out').textContent === 'Hello, Choi'");
        Thread.sleep(1000); // longer than the close grace period
        assertFalse(onUi(() -> window.isClosed()), "a reload must not close the window");
    }

    @Test
    @Order(15)
    void synchronousReadsDoNotDeadlock() throws Exception {
        String current = String.valueOf(cdp.eval("document.getElementById('out').textContent"));
        assertEquals(current, onUi(() -> window.byId("out").getText()));        // on the UI thread
        assertEquals(current, window.byId("out").getText());                    // from another thread
        assertEquals("name", onUi(() -> window.byId("name").getAttribute("id")));
        assertEquals("INPUT", onUi(() -> window.byId("name").getProperty("tagName")));
        cdp.eval("document.getElementById('syncBtn').click()");
        cdp.waitFor("document.getElementById('syncOut').textContent === 'sync:" + current + ":name'");
        assertEquals("caught", onUi(() -> {
            try {
                window.evalSync("throw new Error('nope')");
                return "no error";
            } catch (JsException e) {
                return e.getMessage().contains("nope") ? "caught" : e.getMessage();
            }
        }));
    }

    @Test
    @Order(16)
    void elementsSurviveClearWhileJavaReferencesThem() throws Exception {
        cdp.eval("document.getElementById('reuseBtn').click()");
        cdp.waitFor("document.querySelectorAll('#keptList li').length === 1");
        cdp.eval("document.getElementById('reuseBtn').click()");
        cdp.waitFor("document.querySelectorAll('#keptList li').length === 1"
                + " && document.querySelector('#keptList li').textContent === 'kept'");
        cdp.eval("document.getElementById('reuseStatic').click()");
        Thread.sleep(300);
        cdp.waitFor("document.querySelector('#staticList #staticItem')?.textContent === 'static'");

        // when Java no longer references an element (its proxy was collected), the page releases it
        E2EController controller = E2EController.INSTANCES.get(E2EController.INSTANCES.size() - 1);
        String keptId = controller.kept.getId();
        cdp.eval("document.getElementById('" + keptId + "').remove()");
        long before = ((Number) cdp.eval("hanji.__stats().created")).longValue();
        onUi(() -> {
            window.release(keptId, controller.kept.generation()); // what the Cleaner does after GC
            return null;
        });
        cdp.waitFor("hanji.__stats().created === " + (before - 1));
    }

    @Test
    @Order(17)
    void lateReloadNotificationKeepsTheReadyPage() throws Exception {
        // The event stream thread may report a reload after the POST thread already delivered the new page's
        // 'ready' (two HTTP threads race). That late notification must not reset the page that is now running.
        String pid = String.valueOf(cdp.eval("hanji.pageId"));
        onUi(() -> {
            window.onPageReplaced(pid);
            return null;
        });
        cdp.eval("(() => { const n = document.getElementById('name'); n.value = 'Late';"
                + " n.dispatchEvent(new Event('input', {bubbles: true}));"
                + " document.getElementById('greet').click(); })()");
        cdp.waitFor("document.getElementById('out').textContent === 'Hello, Late'");
    }

    @Test
    @Order(20)
    void programmaticWindowWithComponents() throws Exception {
        Label[] status = new Label[1];
        Window second = onUi(() -> {
            Window w = new Window().setTitle("Programmatic");
            TextField input = new TextField().setPlaceholder("name");
            status[0] = new Label("idle");
            Button button = new Button("Go", e -> status[0].setText("clicked " + input.getText())).setPrimary(true);
            TableView<Person> table = new TableView<>();
            table.addColumn("Name", Person::name);
            table.addColumn("Age", Person::age).setAlign("right");
            table.getItems().setAll(new Person("B", 30), new Person("A", 50), new Person("C", 20));
            w.setContent(new VBox(Label.heading("programmatic"), new HBox(input, button), status[0], table));
            w.show();
            return w;
        });
        cdp.send("Page.navigate", Map.of("url", onUi(() -> second.getUrl().toString())));
        cdp.waitFor("document.querySelector('.hj-vbox .hj-heading')?.textContent === 'programmatic'");
        assertEquals("Programmatic", cdp.eval("document.title"));

        cdp.eval("(() => { const i = document.querySelector('.hj-hbox input'); i.value = 'Jung';"
                + " i.dispatchEvent(new Event('input', {bubbles: true}));"
                + " document.querySelector('.hj-hbox button').click(); })()");
        cdp.waitFor("document.querySelector('.hj-vbox > .hj-label').textContent === 'clicked Jung'");

        cdp.waitFor("document.querySelectorAll('.hj-table tbody tr').length === 3");
        cdp.eval("document.querySelector('.hj-table th[data-col=\"1\"]').click()");
        cdp.waitFor("document.querySelector('.hj-table tbody tr td:last-child').textContent === '20'");
        cdp.eval("document.querySelector('.hj-table th[data-col=\"1\"]').click()");
        cdp.waitFor("document.querySelector('.hj-table tbody tr td:last-child').textContent === '50'");

        // the first window's page was navigated away: it must be detected as closed
        long deadline = System.currentTimeMillis() + 10_000;
        while (!onUi(() -> window.isClosed()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(onUi(() -> window.isClosed()), "first window should close when its page is gone");
        assertTrue(onUi(second::isShowing));
    }

    @Test
    @Order(30)
    void noUnexpectedErrors() {
        assertEquals(List.of(), new ArrayList<>(TestApp.ERRORS));
    }

    // ------------------------------------------------------------------ helpers

    /** Chromium helper processes may still write into the profile while it is being deleted. */
    private static void deleteQuietly(Path dir) {
        for (int attempt = 0; attempt < 5 && Files.exists(dir); attempt++) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException | java.io.UncheckedIOException e) {
                // retry
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        return f.get(10, TimeUnit.SECONDS);
    }

    /** The DevTools port, or null while the file is missing, empty or still locked by Chromium (Windows). */
    private static String readPort(Path portFile) {
        try {
            List<String> lines = Files.readAllLines(portFile);
            return lines.isEmpty() || lines.get(0).isBlank() ? null : lines.get(0).trim();
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String pageWebSocketUrl() throws Exception {
        Path portFile = profile.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + 20_000;
        String port;
        while ((port = readPort(portFile)) == null) {
            if (System.currentTimeMillis() > deadline || !chrome.isAlive()) {
                throw new IOException("Chromium did not start (DevToolsActivePort missing)");
            }
            Thread.sleep(50);
        }
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        while (true) {
            String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            for (Object t : (List<Object>) Json.parse(body)) {
                Map<String, Object> target = (Map<String, Object>) t;
                if ("page".equals(target.get("type"))) {
                    return (String) target.get("webSocketDebuggerUrl");
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("No page target found");
            }
            Thread.sleep(50);
        }
    }
}
