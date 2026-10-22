package io.hanji;

import java.util.List;

/**
 * A Java function callable from page scripts through {@code hanji.call(name, ...args)}.
 * <p>
 * Arguments arrive as plain JSON values ({@code String}, {@code Long}, {@code Double}, {@code Boolean},
 * {@code List}, {@code Map} or {@code null}). The return value is converted to JSON; returning a
 * {@link java.util.concurrent.CompletionStage} resolves the JavaScript promise when it completes.
 */
@FunctionalInterface
public interface JsFunction {

    Object apply(List<Object> args) throws Exception;
}
