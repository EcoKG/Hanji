package io.hanji;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Options for {@link Element#on(String, EventOptions, EventHandler)}. They are applied in the page,
 * so {@code preventDefault} takes effect synchronously:
 * <pre>
 * form.on("submit", EventOptions.of().preventDefault(), e -&gt; save(e.getForm()));
 * search.on("input", EventOptions.of().debounce(300), e -&gt; find(e.getValue()));
 * list.on("click", EventOptions.of().delegate("li[data-id]"), e -&gt; open(e.getData("id")));
 * </pre>
 */
public final class EventOptions {

    private boolean preventDefault;
    private boolean stopPropagation;
    private boolean capture;
    private int debounceMillis;
    private String delegate;
    private List<String> keys;

    private EventOptions() {
    }

    public static EventOptions of() {
        return new EventOptions();
    }

    /** Calls {@code event.preventDefault()} in the page before forwarding. */
    public EventOptions preventDefault() {
        this.preventDefault = true;
        return this;
    }

    /** Calls {@code event.stopPropagation()} in the page before forwarding. */
    public EventOptions stopPropagation() {
        this.stopPropagation = true;
        return this;
    }

    /** Registers the listener for the capture phase. */
    public EventOptions capture() {
        this.capture = true;
        return this;
    }

    /** Forwards only the last event of a burst, after {@code millis} of silence. */
    public EventOptions debounce(int millis) {
        this.debounceMillis = Math.max(0, millis);
        return this;
    }

    /**
     * Event delegation: fire only for events whose target is (inside) a descendant matching {@code selector}.
     * That descendant becomes the event's current target (its id and {@code data-*} attributes are reported).
     */
    public EventOptions delegate(String selector) {
        this.delegate = selector;
        return this;
    }

    /** Only forward keyboard events whose {@code key} is one of {@code keys} (e.g. "Enter", "Escape"). */
    public EventOptions keys(String... keys) {
        this.keys = Arrays.asList(keys);
        return this;
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (preventDefault) {
            m.put("prevent", true);
        }
        if (stopPropagation) {
            m.put("stop", true);
        }
        if (capture) {
            m.put("capture", true);
        }
        if (debounceMillis > 0) {
            m.put("debounce", debounceMillis);
        }
        if (delegate != null && !delegate.isEmpty()) {
            m.put("delegate", delegate);
        }
        if (keys != null && !keys.isEmpty()) {
            m.put("keys", keys);
        }
        return m;
    }
}
