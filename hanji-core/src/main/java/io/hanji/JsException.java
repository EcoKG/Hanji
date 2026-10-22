package io.hanji;

/** An error raised by JavaScript code (for example a failing {@link Window#eval(String)}). */
public class JsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsException(String message) {
        super(message);
    }
}
