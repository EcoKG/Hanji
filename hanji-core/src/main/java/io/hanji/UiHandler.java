package io.hanji;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a controller method as a DOM event handler.
 * <pre>
 * &#64;UiHandler("save")                                  void save() { ... }
 * &#64;UiHandler(value = "query", event = "input", debounce = 300) void search(Event e) { ... }
 * &#64;UiHandler(value = "#document", event = "keydown", keys = "Escape") void cancel() { ... }
 * </pre>
 * The method may take no parameters or a single {@link Event} parameter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UiHandler {

    /** Element ids. {@code #document}, {@code #window} and {@code #body} address the global targets. */
    String[] value();

    /** DOM event type, e.g. {@code click}, {@code input}, {@code change}, {@code submit}, {@code keydown}. */
    String event() default "click";

    boolean preventDefault() default false;

    boolean stopPropagation() default false;

    /** Debounce delay in milliseconds (0 = off). */
    int debounce() default 0;

    /** CSS selector for event delegation (handler fires for descendants matching the selector). */
    String delegate() default "";

    /** Only fire for these {@code KeyboardEvent.key} values, e.g. {@code "Enter"}. */
    String[] keys() default {};
}
