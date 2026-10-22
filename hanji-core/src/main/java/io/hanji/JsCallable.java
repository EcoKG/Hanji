package io.hanji;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a controller method to page scripts: {@code const r = await hanji.call('name', a, b)}.
 * Parameters are converted from JSON to the declared Java types (records and POJOs included);
 * the return value is converted to JSON.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsCallable {

    /** Name visible to JavaScript. Defaults to the method name. */
    String value() default "";

    /**
     * Run on a background worker thread instead of the UI thread. Use for slow work (I/O, database).
     * The method must not touch elements or windows directly; use {@link Hanji#runLater(Runnable)}.
     */
    boolean background() default false;
}
