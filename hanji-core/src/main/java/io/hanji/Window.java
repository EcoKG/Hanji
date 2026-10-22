package io.hanji;

import io.hanji.json.Json;
import io.hanji.json.JsonMapper;
import io.hanji.spi.HostWindow;
import io.hanji.spi.HostWindowSpec;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A top-level window showing one HTML page (the counterpart of a Swing {@code JFrame} or JavaFX {@code Stage}).
 * <pre>
 * public void start(Window window) {
 *     window.setTitle("Todo");
 *     window.load("todo/index.html", new TodoController());   // web/todo/index.html on the classpath
 *     window.show();
 * }
 * </pre>
 * All methods must be called on the Hanji UI thread (see {@link Hanji#runLater(Runnable)}).
 */
public final class Window {

    static final String INLINE_PAGE = "__hanji_page.html";
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum State { NEW, SHOWING, CLOSED }

    private final HanjiRuntime runtime;
    private final String token;
    private final WindowChannel channel = new WindowChannel();

    private String title = "Hanji";
    private boolean titleSet;
    private int width = 1024;
    private int height = 768;
    private Integer x;
    private Integer y;

    private String pagePath;          // relative to the web root; null = inline page
    private String inlineHtml;        // inline page content (null = blank page)
    private boolean pageAssigned;
    private Object controller;
    private Supplier<?> controllerFactory;
    private Object activeController;

    private int generation;
    private String pageId;
    private boolean pageReady;
    private State state = State.NEW;
    private HostWindow hostWindow;

    private List<Map<String, Object>> outgoing = new ArrayList<>();
    private final Map<Long, Listener> listeners = new HashMap<>();
    private final Map<String, String> fieldValues = new HashMap<>();
    private final Map<String, Boolean> fieldChecked = new HashMap<>();
    private final Map<Long, CompletableFuture<Object>> pendingRequests = new HashMap<>();
    private long requestSequence;
    private final Map<String, JsFunction> functions = new LinkedHashMap<>();
    private final Map<String, JsFunction> pageFunctions = new LinkedHashMap<>();
    private final Map<String, List<Consumer<Object>>> messageHandlers = new HashMap<>();
    private final List<Runnable> readyHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> closedHandlers = new CopyOnWriteArrayList<>();

    /** Creates a new (hidden) window. Must be called on the UI thread of a running Hanji application. */
    public Window() {
        this.runtime = HanjiRuntime.get();
        runtime.checkUiThread();
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        this.token = sb.toString();
        runtime.register(this);
    }

    // ================================================================== configuration

    public String getTitle() {
        return title;
    }

    /** Sets the window title (overrides the page's {@code <title>}). */
    public Window setTitle(String title) {
        runtime.checkUiThread();
        this.title = title == null ? "" : title;
        this.titleSet = true;
        if (state == State.SHOWING) {
            sendRaw(Cmd.of("title", "value", this.title));
        }
        return this;
    }

    /** Initial window size in CSS pixels (applied when the window is shown). */
    public Window setSize(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Initial screen position (applied when the window is shown). */
    public Window setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    // ================================================================== pages

    /** Loads an HTML page from the web root, e.g. {@code load("index.html")} for {@code web/index.html}. */
    public Window load(String path) {
        setPage(normalizePath(path), null, null, null);
        return this;
    }

    /** Loads a page and binds {@code controller} ({@code @UiField}, {@code @UiHandler}, {@code @JsCallable}, {@code initialize()}). */
    public Window load(String path, Object controller) {
        setPage(normalizePath(path), null, Objects.requireNonNull(controller, "controller"), null);
        return this;
    }

    /**
     * Loads a page with a controller factory. A new controller is created every time the page is
     * (re)loaded, which makes live reload in dev mode work naturally: {@code load("main.html", MainController::new)}.
     */
    public Window load(String path, Supplier<?> controllerFactory) {
        setPage(normalizePath(path), null, null, Objects.requireNonNull(controllerFactory, "controllerFactory"));
        return this;
    }

    /** Shows the given HTML document. Relative URLs inside it resolve against the web root. */
    public Window loadHtml(String html) {
        setPage(null, Objects.requireNonNull(html, "html"), null, null);
        return this;
    }

    public Window loadHtml(String html, Object controller) {
        setPage(null, Objects.requireNonNull(html, "html"), controller, null);
        return this;
    }

    /**
     * Shows {@code content} as the only child of {@code <body>}. If no page was loaded yet, an empty
     * built-in page is used, so a UI can be built entirely in Java (Swing style).
     */
    public Window setContent(HasElement content) {
        runtime.checkUiThread();
        if (!pageAssigned) {
            setPage(null, null, null, null);
        }
        body().clear().append(content);
        return this;
    }

    private static String normalizePath(String path) {
        Objects.requireNonNull(path, "path");
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Page path must not be empty");
        }
        return p;
    }

    private void setPage(String path, String html, Object controller, Supplier<?> factory) {
        runtime.checkUiThread();
        if (state == State.CLOSED) {
            throw new IllegalStateException("Window is closed");
        }
        this.pagePath = path;
        this.inlineHtml = html;
        this.pageAssigned = true;
        this.controller = controller;
        this.controllerFactory = factory;
        resetPageState();
        if (state == State.SHOWING) {
            channel.push(Json.stringify(List.of(Cmd.of("navigate", "url", getUrl().toString()))));
        }
        channel.expectNewPage();
    }

    /** Clears everything that belongs to the currently displayed page instance. */
    private void resetPageState() {
        generation++;
        pageReady = false;
        pageId = null;
        outgoing = new ArrayList<>();
        listeners.clear();
        pageFunctions.clear();
        activeController = null;
        failPendingRequests(new CancellationException("The page was replaced"));
    }

    /** The URL of the current page (served by the embedded server on 127.0.0.1). */
    public URI getUrl() {
        String path = pagePath != null ? pagePath : INLINE_PAGE;
        return runtime.baseUri().resolve("/" + token + "/" + path);
    }

    /** The controller bound to the current page (created by the factory, if one was given). */
    @SuppressWarnings("unchecked")
    public <T> T getController() {
        return (T) activeController;
    }

    // ================================================================== lifecycle

    public Window show() {
        runtime.checkUiThread();
        if (state == State.SHOWING) {
            return this;
        }
        if (state == State.CLOSED) {
            throw new IllegalStateException("A closed window cannot be shown again");
        }
        if (!pageAssigned) {
            setPage(null, null, null, null);
        }
        state = State.SHOWING;
        try {
            hostWindow = runtime.host().open(new HostWindowSpec(getUrl(), title, width, height, x, y),
                    () -> runtime.post(this::closedByHost));
        } catch (Exception e) {
            state = State.NEW;
            throw new IllegalStateException("Could not open window: " + e.getMessage(), e);
        }
        return this;
    }

    public boolean isShowing() {
        return state == State.SHOWING;
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }

    /** Closes the window. May be called from any thread. */
    public void close() {
        if (!runtime.isUiThread()) {
            runtime.post(this::close);
            return;
        }
        closeInternal(true, true);
    }

    void closeForShutdown() {
        closeInternal(true, false);
    }

    /** The user closed the window (host callback or lost page connection). */
    void closedByHost() {
        closeInternal(false, true);
    }

    void checkConnection() {
        if (state == State.SHOWING && hostWindow != null && hostWindow.closesWithPage() && !channel.isAlive()) {
            Log.debug("Page of window '" + title + "' disconnected; closing window");
            closedByHost();
        }
    }

    private void closeInternal(boolean notifyPage, boolean allowImplicitExit) {
        if (state == State.CLOSED) {
            return;
        }
        boolean wasShowing = state == State.SHOWING;
        if (notifyPage && wasShowing) {
            outgoing.add(Cmd.of("close", "text", runtime.text("closed")));
            flush();
        }
        state = State.CLOSED;
        channel.close();
        if (hostWindow != null) {
            try {
                hostWindow.close();
            } catch (Throwable t) {
                runtime.reportError(t);
            }
        }
        failPendingRequests(new CancellationException("Window closed"));
        listeners.clear();
        for (Runnable r : closedHandlers) {
            try {
                r.run();
            } catch (Throwable t) {
                runtime.reportError(t);
            }
        }
        runtime.windowClosed(this, allowImplicitExit);
    }

    /** Runs {@code handler} every time a page has finished loading and its controller was initialized. */
    public Registration onReady(Runnable handler) {
        readyHandlers.add(Objects.requireNonNull(handler));
        return () -> readyHandlers.remove(handler);
    }

    /** Runs {@code handler} after the window was closed (by the user or by code). */
    public Registration onClosed(Runnable handler) {
        closedHandlers.add(Objects.requireNonNull(handler));
        return () -> closedHandlers.remove(handler);
    }

    public boolean isPageReady() {
        return pageReady;
    }

    // ================================================================== elements

    /** Proxy for the element with the given id in the current page (no round trip; existence is not checked). */
    public Element byId(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        return new Element(this, id);
    }

    public Element body() {
        return new Element(this, "#body");
    }

    public Element head() {
        return new Element(this, "#head");
    }

    /** The {@code document} object as an event target, e.g. {@code document().on("keydown", ...)}. */
    public Element document() {
        return new Element(this, "#document");
    }

    /** The JavaScript {@code window} object as an event target, e.g. for {@code resize}. */
    public Element global() {
        return new Element(this, "#window");
    }

    /** {@code querySelectorAll(selector)}; elements without an id get a generated one. */
    public CompletableFuture<List<Element>> query(String selector) {
        return request(null, Cmd.of("query", "selector", selector)).thenApply(v -> {
            List<Element> out = new ArrayList<>();
            if (v instanceof List<?>) {
                for (Object id : (List<?>) v) {
                    out.add(byId(String.valueOf(id)));
                }
            }
            return out;
        });
    }

    // ================================================================== scripting & messaging

    /** Evaluates JavaScript in the page. Completes with the script's (awaited) completion value as JSON. */
    public CompletableFuture<Object> eval(String script) {
        return request(null, Cmd.of("eval", "script", script));
    }

    public <T> CompletableFuture<T> eval(String script, Class<T> resultType) {
        return eval(script).thenApply(v -> JsonMapper.fromJson(v, resultType));
    }

    /** Calls a global JavaScript function (dotted paths allowed, e.g. {@code "charts.update"}). Fire and forget. */
    public void call(String function, Object... args) {
        List<Object> plain = new ArrayList<>(args.length);
        for (Object a : args) {
            plain.add(JsonMapper.toPlain(a));
        }
        sendRaw(Cmd.of("call", "fn", function, "args", plain));
    }

    /** Delivers {@code data} to page handlers registered with {@code hanji.on(topic, fn)}. */
    public void publish(String topic, Object data) {
        sendRaw(Cmd.of("publish", "topic", topic, "data", JsonMapper.toPlain(data)));
    }

    /** Handles messages sent by the page with {@code hanji.emit(name, payload)}. */
    public Registration onMessage(String name, Consumer<Object> handler) {
        List<Consumer<Object>> list = messageHandlers.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }

    /** Like {@link #onMessage(String, Consumer)} with the payload converted to {@code type}. */
    public <T> Registration onMessage(String name, Class<T> type, Consumer<? super T> handler) {
        return onMessage(name, payload -> handler.accept(JsonMapper.fromJson(payload, type)));
    }

    /** Makes {@code function} callable from the page as {@code hanji.call(name, ...args)} (for every page of this window). */
    public Registration expose(String name, JsFunction function) {
        functions.put(Objects.requireNonNull(name), Objects.requireNonNull(function));
        return () -> functions.remove(name, function);
    }

    void exposeForPage(String name, JsFunction function) {
        pageFunctions.put(name, function);
    }

    // ================================================================== dialogs (HTML <dialog>, non-blocking)

    public CompletableFuture<Void> alert(String message) {
        return alert(null, message);
    }

    public CompletableFuture<Void> alert(String title, String message) {
        return dialog("alert", title, message, null).thenApply(v -> null);
    }

    /** Completes with {@code true} if the user pressed OK. */
    public CompletableFuture<Boolean> confirm(String message) {
        return confirm(null, message);
    }

    public CompletableFuture<Boolean> confirm(String title, String message) {
        return dialog("confirm", title, message, null).thenApply(Boolean.TRUE::equals);
    }

    /** Completes with the entered text, or {@code null} if cancelled. */
    public CompletableFuture<String> prompt(String message, String defaultValue) {
        return prompt(null, message, defaultValue);
    }

    public CompletableFuture<String> prompt(String title, String message, String defaultValue) {
        return dialog("prompt", title, message, defaultValue).thenApply(v -> v == null ? null : String.valueOf(v));
    }

    private CompletableFuture<Object> dialog(String kind, String dialogTitle, String message, String value) {
        Map<String, Object> cmd = Cmd.of("dialog", "kind", kind, "title", dialogTitle, "message", message,
                "value", value, "okText", runtime.text("ok"), "cancelText", runtime.text("cancel"));
        return request(null, cmd);
    }

    // ================================================================== internals: outgoing

    int generation() {
        return generation;
    }

    String token() {
        return token;
    }

    WindowChannel channel() {
        return channel;
    }

    String inlineHtml() {
        return inlineHtml;
    }

    String pagePath() {
        return pagePath;
    }

    String titleText() {
        return title;
    }

    void send(Element element, Map<String, Object> cmd) {
        runtime.checkUiThread();
        if (state == State.CLOSED || (element != null && element.generation() != generation)) {
            return;
        }
        outgoing.add(cmd);
    }

    private void sendRaw(Map<String, Object> cmd) {
        send(null, cmd);
    }

    CompletableFuture<Object> request(Element element, Map<String, Object> cmd) {
        runtime.checkUiThread();
        if (state == State.CLOSED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Window is closed"));
        }
        if (element != null && element.generation() != generation) {
            return CompletableFuture.failedFuture(new IllegalStateException("Element belongs to a previous page"));
        }
        long rid = ++requestSequence;
        cmd.put("rid", rid);
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingRequests.put(rid, future);
        outgoing.add(cmd);
        return future;
    }

    /** Pushes the commands collected during the current UI task to the page. */
    void flush() {
        if (outgoing.isEmpty()) {
            return;
        }
        List<Map<String, Object>> batch = outgoing;
        outgoing = new ArrayList<>();
        String json;
        try {
            json = Json.stringify(batch);
        } catch (RuntimeException e) {
            runtime.reportError(e);
            return;
        }
        Log.debug("-> '" + title + "' " + json);
        channel.push(json);
    }

    private void failPendingRequests(Throwable reason) {
        if (pendingRequests.isEmpty()) {
            return;
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>(pendingRequests.values());
        pendingRequests.clear();
        for (CompletableFuture<Object> f : futures) {
            f.completeExceptionally(reason);
        }
    }

    // ================================================================== internals: listeners & form state

    static final class Listener {
        final long id;
        final Element element;
        final EventHandler handler;

        Listener(long id, Element element, EventHandler handler) {
            this.id = id;
            this.element = element;
            this.handler = handler;
        }
    }

    void addListener(Listener l) {
        if (l.element.generation() == generation) {
            listeners.put(l.id, l);
        }
    }

    void removeListener(long lid) {
        listeners.remove(lid);
    }

    String fieldValue(String id, String fallback) {
        String v = fieldValues.get(id);
        return v != null ? v : fallback;
    }

    boolean fieldChecked(String id, boolean fallback) {
        Boolean v = fieldChecked.get(id);
        return v != null ? v : fallback;
    }

    void putFieldState(Element element, String value, Boolean checked) {
        if (element.generation() != generation) {
            return;
        }
        if (value != null) {
            fieldValues.put(element.getId(), value);
        }
        if (checked != null) {
            fieldChecked.put(element.getId(), checked);
        }
    }

    // ================================================================== internals: incoming

    /** A different page instance connected (e.g. reload): forget the old page and wait for its ready message. */
    void onPageReplaced(String newPageId) {
        if (state == State.CLOSED) {
            return;
        }
        Log.info("Page of window '" + title + "' was reloaded; re-initializing");
        resetPageState();
    }

    void onUnload() {
        runtime.scheduleConnectionCheck(this);
    }

    void onMessages(String pid, List<?> messages) {
        if (state == State.CLOSED) {
            return;
        }
        for (Object o : messages) {
            if (!(o instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            String type = String.valueOf(m.get("type"));
            try {
                if ("ready".equals(type)) {
                    handleReady(pid, m);
                    continue;
                }
                if ("log".equals(type)) {
                    logClient(m);
                    continue;
                }
                if (!pageReady || !pid.equals(pageId)) {
                    continue;
                }
                switch (type) {
                    case "sync": handleSync(m); break;
                    case "event": handleEvent(m); break;
                    case "result": handleResult(m); break;
                    case "call": handleCall(m); break;
                    case "emit": handleEmit(m); break;
                    default: Log.warn("Unknown message from page: " + type);
                }
            } catch (Throwable t) {
                runtime.reportError(t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReady(String pid, Map<String, Object> m) {
        if (pageReady) {
            if (pid.equals(pageId)) {
                return;
            }
            resetPageState();
        }
        pageId = pid;
        pageReady = true;
        fieldValues.clear();
        fieldChecked.clear();
        Object values = m.get("values");
        if (values instanceof Map<?, ?>) {
            ((Map<String, Object>) values).forEach((id, state) -> {
                if (state instanceof Map<?, ?>) {
                    Map<String, Object> s = (Map<String, Object>) state;
                    if (s.get("value") != null) {
                        fieldValues.put(id, String.valueOf(s.get("value")));
                    }
                    if (s.get("checked") instanceof Boolean) {
                        fieldChecked.put(id, (Boolean) s.get("checked"));
                    }
                }
            });
        }
        if (titleSet) {
            sendRaw(Cmd.of("title", "value", title));
        } else if (m.get("title") instanceof String && !((String) m.get("title")).isEmpty()) {
            title = (String) m.get("title");
        }
        Object ctrl = controllerFactory != null ? controllerFactory.get() : controller;
        activeController = ctrl;
        if (ctrl != null) {
            try {
                ControllerBinder.bind(this, ctrl);
            } catch (InvocationTargetException e) {
                runtime.reportError(e.getCause() != null ? e.getCause() : e);
            } catch (Throwable t) {
                runtime.reportError(t);
            }
        }
        for (Runnable r : readyHandlers) {
            try {
                r.run();
            } catch (Throwable t) {
                runtime.reportError(t);
            }
        }
    }

    private void handleSync(Map<String, Object> m) {
        String id = (String) m.get("id");
        if (id == null) {
            return;
        }
        if (m.get("value") != null) {
            fieldValues.put(id, String.valueOf(m.get("value")));
        }
        if (m.get("checked") instanceof Boolean) {
            fieldChecked.put(id, (Boolean) m.get("checked"));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEvent(Map<String, Object> m) {
        Listener l = listeners.get(asLong(m.get("lid")));
        if (l == null) {
            return;
        }
        Map<String, Object> data = m.get("data") instanceof Map ? (Map<String, Object>) m.get("data") : new HashMap<>();
        Object valueId = data.get("valueId");
        if (valueId != null) {
            if (data.get("value") != null) {
                fieldValues.put(String.valueOf(valueId), String.valueOf(data.get("value")));
            }
            if (data.get("checked") instanceof Boolean) {
                fieldChecked.put(String.valueOf(valueId), (Boolean) data.get("checked"));
            }
        }
        l.handler.handle(new Event(this, data));
    }

    private void handleResult(Map<String, Object> m) {
        CompletableFuture<Object> f = pendingRequests.remove(asLong(m.get("rid")));
        if (f == null) {
            return;
        }
        if (m.containsKey("error")) {
            f.completeExceptionally(new JsException(String.valueOf(m.get("error"))));
        } else {
            f.complete(m.get("value"));
        }
    }

    private void handleCall(Map<String, Object> m) {
        long cid = asLong(m.get("cid"));
        String name = String.valueOf(m.get("name"));
        List<?> rawArgs = m.get("args") instanceof List ? (List<?>) m.get("args") : List.of();
        JsFunction fn = pageFunctions.containsKey(name) ? pageFunctions.get(name) : functions.get(name);
        if (fn == null) {
            reply(cid, null, new IllegalArgumentException("No Java function named '" + name + "'"));
            return;
        }
        Object result;
        try {
            result = fn.apply(new ArrayList<>(rawArgs));
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            runtime.reportError(cause);
            reply(cid, null, cause);
            return;
        }
        if (result instanceof CompletionStage<?>) {
            int gen = generation;
            ((CompletionStage<?>) result).whenComplete((value, error) -> runtime.post(() -> {
                if (gen == generation && state != State.CLOSED) {
                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    reply(cid, value, cause);
                }
            }));
        } else {
            reply(cid, result, null);
        }
    }

    private void reply(long cid, Object value, Throwable error) {
        if (error == null) {
            try {
                sendRaw(Cmd.of("reply", "cid", cid, "value", JsonMapper.toPlain(value)));
                return;
            } catch (RuntimeException e) {
                error = e;
            }
        }
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        sendRaw(Cmd.of("reply", "cid", cid, "error", message));
    }

    private void handleEmit(Map<String, Object> m) {
        List<Consumer<Object>> handlers = messageHandlers.get(String.valueOf(m.get("name")));
        if (handlers == null || handlers.isEmpty()) {
            Log.debug("No handler for page message '" + m.get("name") + "'");
            return;
        }
        for (Consumer<Object> h : handlers) {
            h.accept(m.get("payload"));
        }
    }

    private void logClient(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("[hanji:js] ").append(m.get("message"));
        if (m.get("source") != null) {
            sb.append(" (").append(m.get("source")).append(')');
        }
        if (m.get("stack") != null) {
            sb.append(System.lineSeparator()).append(m.get("stack"));
        }
        System.err.println(sb);
    }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }

    @Override
    public String toString() {
        return "Window[" + title + "]";
    }
}
