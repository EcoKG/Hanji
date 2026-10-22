package io.hanji;

import io.hanji.json.JsonMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DOM event forwarded from the page. Only a serializable snapshot is available (the event already
 * happened in the browser); use {@link EventOptions} for {@code preventDefault}/{@code stopPropagation}.
 */
public final class Event {

    private final Window window;
    private final Map<String, Object> data;

    Event(Window window, Map<String, Object> data) {
        this.window = window;
        this.data = data;
    }

    public Window getWindow() {
        return window;
    }

    public String getType() {
        return string("type");
    }

    /** Id of the element the event originated from ({@code null} if it has no id). */
    public String getTargetId() {
        return string("targetId");
    }

    /** Id of the element the listener is registered on, or the matched element for delegated listeners. */
    public String getCurrentTargetId() {
        return string("currentId");
    }

    public Element getTarget() {
        String id = getTargetId();
        return id == null ? null : window.byId(id);
    }

    public Element getCurrentTarget() {
        String id = getCurrentTargetId();
        return id == null ? null : window.byId(id);
    }

    /** Value of the form field involved in the event (input/select/textarea), or {@code null}. */
    public String getValue() {
        return string("value");
    }

    /** Checked state of a checkbox/radio involved in the event. */
    public boolean isChecked() {
        return Boolean.TRUE.equals(data.get("checked"));
    }

    /** {@code KeyboardEvent.key}, e.g. "Enter", "a", "ArrowUp". */
    public String getKey() {
        return string("key");
    }

    /** {@code KeyboardEvent.code}, e.g. "KeyA", "Enter". */
    public String getCode() {
        return string("code");
    }

    /** Mouse button (0 = primary) or -1 for non-mouse events. */
    public int getButton() {
        return integer("button", -1);
    }

    public double getClientX() {
        return number("clientX");
    }

    public double getClientY() {
        return number("clientY");
    }

    public boolean isAltDown() {
        return Boolean.TRUE.equals(data.get("altKey"));
    }

    public boolean isControlDown() {
        return Boolean.TRUE.equals(data.get("ctrlKey"));
    }

    public boolean isShiftDown() {
        return Boolean.TRUE.equals(data.get("shiftKey"));
    }

    public boolean isMetaDown() {
        return Boolean.TRUE.equals(data.get("metaKey"));
    }

    /** {@code data-*} attributes of the current target (camelCase keys, as in {@code element.dataset}). */
    @SuppressWarnings("unchecked")
    public Map<String, String> getDataset() {
        Object ds = data.get("dataset");
        if (!(ds instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<String, Object>) ds).forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    /** Shortcut for {@code getDataset().get(key)}; {@code data-item-id} is read as {@code "itemId"}. */
    public String getData(String key) {
        return getDataset().get(key);
    }

    /** For {@code submit} events: the form fields as name -&gt; value (repeated names become lists). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getForm() {
        Object f = data.get("form");
        return f instanceof Map ? (Map<String, Object>) f : Collections.emptyMap();
    }

    /** Converts the submitted form to a record/POJO, e.g. {@code e.getForm(Person.class)}. */
    public <T> T getForm(Class<T> type) {
        return JsonMapper.fromJson(getForm(), type);
    }

    /** {@code CustomEvent.detail} as a plain JSON value. */
    public Object getDetail() {
        return data.get("detail");
    }

    public <T> T getDetail(Class<T> type) {
        return JsonMapper.fromJson(getDetail(), type);
    }

    /** Raw event snapshot as sent by the page. */
    public Map<String, Object> getRaw() {
        return Collections.unmodifiableMap(data);
    }

    private String string(String key) {
        Object v = data.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private double number(String key) {
        Object v = data.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private int integer(String key, int fallback) {
        Object v = data.get(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    @Override
    public String toString() {
        return "Event" + data;
    }
}
