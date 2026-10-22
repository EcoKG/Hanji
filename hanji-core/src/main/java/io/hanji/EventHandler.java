package io.hanji;

/** Handles a DOM event forwarded from the page. Always invoked on the Hanji UI thread. */
@FunctionalInterface
public interface EventHandler {

    void handle(Event event);
}
