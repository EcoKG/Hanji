package io.hanji;

import io.hanji.json.Json;
import io.hanji.json.JsonMapper;
import io.hanji.property.ObservableValue;
import io.hanji.property.Property;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java proxy of a DOM element.
 * <p>
 * Elements come from two places:
 * <ul>
 *   <li>{@link Window#byId(String)} / {@code @UiField}: an element that exists in the loaded HTML page.</li>
 *   <li>{@link #create(String)}: a new element. Until it is appended to an element of a window, all
 *       operations are recorded and replayed in order once it is attached (so trees can be built
 *       up-front, Swing style).</li>
 * </ul>
 * Operations are sent to the page asynchronously in batches at the end of each UI task. Element methods
 * must be called on the Hanji UI thread once the element is attached to a window.
 * <p>
 * Form state ({@link #getValue()}, {@link #isChecked()}) is mirrored automatically for {@code input},
 * {@code select} and {@code textarea} elements that have an id, so it can be read synchronously.
 */
public final class Element implements HasElement {

    private static final Cleaner CLEANER = Cleaner.create(r -> {
        Thread t = new Thread(r, "Hanji-Cleaner");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong ID_SEQUENCE = new AtomicLong();
    private static final AtomicLong ORDER_SEQUENCE = new AtomicLong();
    private static final AtomicLong LISTENER_SEQUENCE = new AtomicLong();

    private final String id;
    private final String tag;
    private final String namespace;
    private final boolean created;

    private Window window;
    private int generation;
    private boolean disposed;
    private boolean visible = true;

    // Recorded while not attached to a window
    private List<PendingCommand> pending;
    private List<Element> pendingChildren;
    private List<Window.Listener> pendingListeners;

    // Local form state (authoritative while detached, fallback once attached)
    private String value = "";
    private boolean checked;
    private boolean valueSet;
    private boolean checkedSet;

    private List<Long> listenerIds;
    private Cleaner.Cleanable cleanable;

    /** Proxy for an element that already exists in the window's page. */
    Element(Window window, String id) {
        this.id = Objects.requireNonNull(id, "id");
        this.tag = null;
        this.namespace = null;
        this.created = false;
        this.window = window;
        this.generation = window.generation();
        track();
    }

    private Element(String namespace, String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("tag must not be empty");
        }
        this.id = "hj-" + ID_SEQUENCE.incrementAndGet();
        this.tag = tag;
        this.namespace = namespace;
        this.created = true;
        this.pending = new ArrayList<>();
    }

    /** Creates a new, detached element, e.g. {@code Element.create("div")}. */
    public static Element create(String tag) {
        return new Element((String) null, tag);
    }

    /** Creates a namespaced element, e.g. {@code Element.createNS("http://www.w3.org/2000/svg", "circle")}. */
    public static Element createNS(String namespace, String tag) {
        return new Element(Objects.requireNonNull(namespace), tag);
    }

    @Override
    public Element element() {
        return this;
    }

    public String getId() {
        return id;
    }

    /** Tag name for created elements; {@code null} for elements looked up by id. */
    public String getTag() {
        return tag;
    }

    /** The window this element is attached to, or {@code null} while detached. */
    public Window getWindow() {
        return window;
    }

    public boolean isAttached() {
        return window != null;
    }

    int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ content

    /** Sets {@code textContent} (HTML is not interpreted). */
    public Element setText(String text) {
        return set("textContent", text == null ? "" : text);
    }

    /** Sets {@code innerHTML}. Never pass unescaped user input; see {@link Html#escape(String)}. */
    public Element setHtml(String html) {
        return set("innerHTML", html == null ? "" : html);
    }

    /** Appends HTML at the end of this element ({@code insertAdjacentHTML('beforeend', html)}). */
    public Element appendHtml(String html) {
        return insertHtml("beforeend", html);
    }

    /** {@code insertAdjacentHTML(position, html)}; position is beforebegin, afterbegin, beforeend or afterend. */
    public Element insertHtml(String position, String html) {
        emit(command("html", "pos", position, "html", html == null ? "" : html));
        return this;
    }

    // ------------------------------------------------------------------ form state

    /** Current value of a form field (mirrored from the page; no round trip). */
    public String getValue() {
        return window != null ? window.fieldValue(id, value) : value;
    }

    public Element setValue(String newValue) {
        String v = newValue == null ? "" : newValue;
        value = v;
        valueSet = true;
        if (window != null) {
            window.putFieldState(this, v, null);
        }
        return set("value", v);
    }

    public boolean isChecked() {
        return window != null ? window.fieldChecked(id, checked) : checked;
    }

    public Element setChecked(boolean newChecked) {
        checked = newChecked;
        checkedSet = true;
        if (window != null) {
            window.putFieldState(this, null, newChecked);
        }
        return set("checked", newChecked);
    }

    // ------------------------------------------------------------------ attributes, properties, classes, styles

    public Element setAttribute(String name, Object attributeValue) {
        emit(command("attr", "name", name, "value", attributeValue == null ? null : String.valueOf(attributeValue)));
        return this;
    }

    public Element removeAttribute(String name) {
        return setAttribute(name, null);
    }

    /** Sets a raw DOM property, e.g. {@code setProperty("selectedIndex", 2)}. */
    public Element setProperty(String name, Object propertyValue) {
        return set(name, JsonMapper.toPlain(propertyValue));
    }

    private Element set(String prop, Object v) {
        emit(command("set", "prop", prop, "value", v));
        return this;
    }

    public Element addClass(String... classNames) {
        for (String c : classNames) {
            toggleClass(c, true);
        }
        return this;
    }

    public Element removeClass(String... classNames) {
        for (String c : classNames) {
            toggleClass(c, false);
        }
        return this;
    }

    public Element toggleClass(String className, boolean on) {
        emit(command("cls", "name", className, "on", on));
        return this;
    }

    /** Sets an inline style. Accepts CSS names ({@code font-size}) or camelCase ({@code fontSize}); null removes it. */
    public Element setStyle(String property, String styleValue) {
        emit(command("style", "name", cssName(property), "value", styleValue));
        return this;
    }

    /** Shows/hides the element with a {@code display:none !important} class (independent of page CSS). */
    public Element setVisible(boolean visible) {
        this.visible = visible;
        return toggleClass("hj-hidden", !visible);
    }

    /** Visibility as last set from Java. */
    public boolean isVisible() {
        return visible;
    }

    /** Sets the {@code disabled} property. */
    public Element setEnabled(boolean enabled) {
        return set("disabled", !enabled);
    }

    /** Sets the tooltip ({@code title} attribute). */
    public Element setTooltip(String text) {
        return setAttribute("title", text);
    }

    // ------------------------------------------------------------------ tree

    /** Appends children (elements or components). Detached children get attached together with this element. */
    public Element append(HasElement... children) {
        for (HasElement h : children) {
            Element child = h.element();
            adopt(child);
            emit(command("append", "child", child.id));
        }
        return this;
    }

    public Element prepend(HasElement child) {
        Element c = child.element();
        adopt(c);
        emit(command("prepend", "child", c.id));
        return this;
    }

    /** Inserts {@code child} before {@code reference}, which must be a child of this element. */
    public Element insertBefore(HasElement child, HasElement reference) {
        Element c = child.element();
        adopt(c);
        emit(command("insert", "child", c.id, "ref", reference.element().id));
        return this;
    }

    /** Detaches this element from its parent. It can be appended again later. */
    public Element remove() {
        emit(command("remove"));
        return this;
    }

    /** Removes all children. Removed children are discarded in the page; append new elements instead of reusing them. */
    public Element clear() {
        if (pendingChildren != null) {
            pendingChildren.clear();
        }
        emit(command("clear"));
        return this;
    }

    /** Removes this element and releases it in the page and its listeners in Java. The proxy becomes inert. */
    public void dispose() {
        emit(command("dispose"));
        if (window != null && listenerIds != null) {
            for (Long lid : listenerIds) {
                window.removeListener(lid);
            }
        }
        disposed = true;
        if (cleanable != null) {
            cleanable.clean();
        }
    }

    private void adopt(Element child) {
        if (child == this) {
            throw new IllegalArgumentException("An element cannot be appended to itself");
        }
        if (window != null) {
            if (child.window == null) {
                child.attachTo(window);
            } else if (child.window != window) {
                throw new IllegalStateException("Element #" + child.id + " belongs to another window");
            }
        } else if (child.window == null) {
            if (pendingChildren == null) {
                pendingChildren = new ArrayList<>();
            }
            pendingChildren.add(child);
        }
    }

    /** Binds this detached element (and its detached subtree) to {@code w} and replays recorded operations. */
    void attachTo(Window w) {
        if (window == w) {
            return;
        }
        if (window != null) {
            throw new IllegalStateException("Element #" + id + " belongs to another window");
        }
        List<Element> subtree = new ArrayList<>();
        collectDetached(this, subtree, Collections.newSetFromMap(new IdentityHashMap<>()));
        for (Element e : subtree) {
            e.window = w;
            e.generation = w.generation();
            e.track();
        }
        for (Element e : subtree) {
            if (e.created) {
                w.send(e, e.namespace == null
                        ? Cmd.of("create", "id", e.id, "tag", e.tag)
                        : Cmd.of("create", "id", e.id, "tag", e.tag, "ns", e.namespace));
            }
        }
        List<PendingCommand> commands = new ArrayList<>();
        for (Element e : subtree) {
            if (e.pending != null) {
                commands.addAll(e.pending);
            }
        }
        commands.sort(Comparator.comparingLong(PendingCommand::order));
        for (PendingCommand pc : commands) {
            w.send(pc.element(), pc.command());
        }
        for (Element e : subtree) {
            if (e.pendingListeners != null) {
                for (Window.Listener l : e.pendingListeners) {
                    w.addListener(l);
                }
            }
            if (e.valueSet || e.checkedSet) {
                w.putFieldState(e, e.valueSet ? e.value : null, e.checkedSet ? e.checked : null);
            }
            e.pending = null;
            e.pendingChildren = null;
            e.pendingListeners = null;
        }
    }

    /**
     * Keeps the page element reachable while this proxy is alive, so that elements removed by {@link #clear()}
     * or {@link #setHtml} can be appended again. When the proxy is garbage collected the page may forget it.
     */
    private void track() {
        window.retain(id, generation, created);
        cleanable = CLEANER.register(this, new Release(window, id, generation));
    }

    /** Cleaner action; must not reference the Element. */
    private static final class Release implements Runnable {
        private final Window window;
        private final String id;
        private final int generation;

        Release(Window window, String id, int generation) {
            this.window = window;
            this.id = id;
            this.generation = generation;
        }

        @Override
        public void run() {
            window.releaseLater(id, generation);
        }
    }

    private static void collectDetached(Element e, List<Element> out, Set<Element> seen) {
        if (e.window != null || !seen.add(e)) {
            return;
        }
        out.add(e);
        if (e.pendingChildren != null) {
            for (Element child : e.pendingChildren) {
                collectDetached(child, out, seen);
            }
        }
    }

    // ------------------------------------------------------------------ methods & scripts

    /** Calls a DOM method on the element, e.g. {@code invoke("setSelectionRange", 0, 3)}. */
    public Element invoke(String method, Object... args) {
        List<Object> plain = new ArrayList<>(args.length);
        for (Object a : args) {
            plain.add(JsonMapper.toPlain(a));
        }
        emit(command("invoke", "method", method, "args", plain));
        return this;
    }

    public Element focus() {
        return invoke("focus");
    }

    public Element blur() {
        return invoke("blur");
    }

    public Element click() {
        return invoke("click");
    }

    public Element scrollIntoView() {
        return invoke("scrollIntoView");
    }

    /**
     * Evaluates JavaScript with this element available as {@code el} (and {@code this}).
     * The completion value of the script (awaited if it is a promise) is returned as a JSON value.
     * <pre>el.eval("el.getBoundingClientRect().width").thenAccept(w -&gt; ...)</pre>
     */
    public CompletableFuture<Object> eval(String script) {
        if (window == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Element #" + id + " is not attached to a window"));
        }
        return window.request(this, command("eval", "script", script));
    }

    /** Evaluates JavaScript with this element as {@code el} and waits for the result (see {@link Window#evalSync}). */
    public Object evalSync(String script) {
        if (window == null) {
            throw new IllegalStateException("Element #" + id + " is not attached to a window");
        }
        return window.syncRequest(() -> eval(script));
    }

    /** Current {@code textContent} read from the page (a round trip; see {@link Window#evalSync}). */
    public String getText() {
        Object v = evalSync("el.textContent");
        return v == null ? "" : String.valueOf(v);
    }

    /** Current {@code innerHTML} read from the page. */
    public String getHtml() {
        Object v = evalSync("el.innerHTML");
        return v == null ? "" : String.valueOf(v);
    }

    /** Attribute value read from the page ({@code null} if absent). */
    public String getAttribute(String name) {
        Object v = evalSync("el.getAttribute(" + Json.stringify(name) + ")");
        return v == null ? null : String.valueOf(v);
    }

    /** Any DOM property read from the page as a JSON value, e.g. {@code getProperty("offsetWidth")}. */
    public Object getProperty(String name) {
        return evalSync("el[" + Json.stringify(name) + "]");
    }

    // ------------------------------------------------------------------ events

    public Registration on(String type, EventHandler handler) {
        return on(type, null, handler);
    }

    /** Registers a DOM event listener. The handler runs on the Hanji UI thread. */
    public Registration on(String type, EventOptions options, EventHandler handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        long lid = LISTENER_SEQUENCE.incrementAndGet();
        Window.Listener listener = new Window.Listener(lid, this, handler);
        if (listenerIds == null) {
            listenerIds = new ArrayList<>(2);
        }
        listenerIds.add(lid);
        if (window != null) {
            window.addListener(listener);
        } else {
            if (pendingListeners == null) {
                pendingListeners = new ArrayList<>();
            }
            pendingListeners.add(listener);
        }
        Map<String, Object> cmd = command("listen", "lid", lid, "type", type);
        if (options != null) {
            Map<String, Object> opts = options.toJson();
            if (!opts.isEmpty()) {
                cmd.put("opts", opts);
            }
        }
        emit(cmd);
        return () -> {
            if (window != null) {
                window.removeListener(lid);
            } else if (pendingListeners != null) {
                pendingListeners.remove(listener);
            }
            listenerIds.remove(lid);
            emit(command("unlisten", "lid", lid));
        };
    }

    public Registration onClick(EventHandler handler) {
        return on("click", handler);
    }

    /** Fires on every value change of a form field ({@code input} event). */
    public Registration onInput(EventHandler handler) {
        return on("input", handler);
    }

    public Registration onChange(EventHandler handler) {
        return on("change", handler);
    }

    // ------------------------------------------------------------------ bindings

    /** Keeps {@code textContent} equal to {@code String.valueOf(source)} ({@code null} renders as empty). */
    public Registration bindText(ObservableValue<?> source) {
        return source.subscribe(v -> setText(v == null ? "" : String.valueOf(v)));
    }

    public Registration bindHtml(ObservableValue<String> source) {
        return source.subscribe(this::setHtml);
    }

    public Registration bindVisible(ObservableValue<Boolean> source) {
        return source.subscribe(v -> setVisible(Boolean.TRUE.equals(v)));
    }

    public Registration bindEnabled(ObservableValue<Boolean> source) {
        return source.subscribe(v -> setEnabled(Boolean.TRUE.equals(v)));
    }

    public Registration bindClass(String className, ObservableValue<Boolean> source) {
        return source.subscribe(v -> toggleClass(className, Boolean.TRUE.equals(v)));
    }

    public Registration bindAttribute(String name, ObservableValue<?> source) {
        return source.subscribe(v -> setAttribute(name, v));
    }

    /** Two-way binding between a form field's value and {@code property}. */
    public Registration bindValue(Property<String> property) {
        boolean[] fromPage = {false};
        Registration toPage = property.subscribe(v -> {
            if (!fromPage[0]) {
                setValue(v);
            }
        });
        Registration fromPageReg = on("input", e -> {
            fromPage[0] = true;
            try {
                property.set(e.getValue());
            } finally {
                fromPage[0] = false;
            }
        });
        return Registration.combine(toPage, fromPageReg);
    }

    /** Two-way binding between a checkbox/radio's checked state and {@code property}. */
    public Registration bindChecked(Property<Boolean> property) {
        boolean[] fromPage = {false};
        Registration toPage = property.subscribe(v -> {
            if (!fromPage[0]) {
                setChecked(Boolean.TRUE.equals(v));
            }
        });
        Registration fromPageReg = on("change", e -> {
            fromPage[0] = true;
            try {
                property.set(e.isChecked());
            } finally {
                fromPage[0] = false;
            }
        });
        return Registration.combine(toPage, fromPageReg);
    }

    // ------------------------------------------------------------------ internals

    private Map<String, Object> command(String op, Object... keyValues) {
        Map<String, Object> m = Cmd.of(op, keyValues);
        m.put("id", id);
        return m;
    }

    private void emit(Map<String, Object> cmd) {
        if (disposed) {
            return;
        }
        if (window != null) {
            window.send(this, cmd);
        } else {
            pending.add(new PendingCommand(ORDER_SEQUENCE.incrementAndGet(), this, cmd));
        }
    }

    /** Recorded commands of a detached element (for tests). */
    List<Map<String, Object>> pendingCommands() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pending != null) {
            for (PendingCommand pc : pending) {
                out.add(pc.command());
            }
        }
        return out;
    }

    static String cssName(String property) {
        if (property.startsWith("--") || property.indexOf('-') >= 0) {
            return property;
        }
        StringBuilder sb = new StringBuilder(property.length() + 4);
        for (int i = 0; i < property.length(); i++) {
            char c = property.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Element)) {
            return false;
        }
        Element other = (Element) o;
        return id.equals(other.id) && window == other.window;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Element#" + id;
    }

    private record PendingCommand(long order, Element element, Map<String, Object> command) {
    }
}
