package io.hanji;

import java.util.List;

/**
 * Base class for Hanji applications (comparable to {@code javafx.application.Application}).
 * <pre>
 * public class TodoApp extends HanjiApplication {
 *     public void start(Window window) {
 *         window.setTitle("Todo").load("index.html", new TodoController()).show();
 *     }
 *     public static void main(String[] args) { launch(args); }
 * }
 * </pre>
 * Lifecycle: constructor &rarr; {@link #configure} &rarr; {@link #init} &rarr; {@link #start} &rarr;
 * ... &rarr; {@link #stop}. All methods run on the Hanji UI thread.
 */
public abstract class HanjiApplication {

    List<String> parameters = List.of();

    /** Adjust settings before the runtime starts (host, web root, dev mode ...). */
    public void configure(HanjiConfig config) {
    }

    public void init() throws Exception {
    }

    /** Builds and shows the primary window. */
    public abstract void start(Window window) throws Exception;

    /** Called once when the application exits. */
    public void stop() throws Exception {
    }

    public final List<String> getParameters() {
        return parameters;
    }

    /** Launches the subclass this method is called from. Blocks until the application exits. */
    public static void launch(String... args) {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (!HanjiApplication.class.isAssignableFrom(caller)) {
            throw new IllegalStateException("HanjiApplication.launch(args) must be called from a HanjiApplication "
                    + "subclass; use Hanji.launch(MyApp.class, args) instead");
        }
        Hanji.launch(caller.asSubclass(HanjiApplication.class), args);
    }
}
