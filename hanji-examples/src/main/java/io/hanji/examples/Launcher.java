package io.hanji.examples;

import io.hanji.Hanji;
import io.hanji.examples.components.ComponentsApp;
import io.hanji.examples.monitor.MonitorApp;
import io.hanji.examples.todo.TodoApp;

import java.util.Arrays;

/** Starts one of the examples: {@code todo} (default), {@code components} or {@code monitor}. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        String example = args.length > 0 ? args[0] : "todo";
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (example) {
            case "todo":
                Hanji.launch(TodoApp.class, rest);
                break;
            case "components":
                Hanji.launch(ComponentsApp.class, rest);
                break;
            case "monitor":
                Hanji.launch(MonitorApp.class, rest);
                break;
            default:
                System.err.println("Unknown example '" + example + "'. Use: todo | components | monitor");
                System.exit(2);
        }
    }
}
