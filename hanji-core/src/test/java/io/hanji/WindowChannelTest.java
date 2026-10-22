package io.hanji;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Close detection of browser-based windows: a reload must not look like a closed window, however slow it is. */
class WindowChannelTest {

    private final WindowChannel channel = new WindowChannel();
    private Thread stream;

    private void connect(String pageId) throws InterruptedException {
        long before = channel.connections();
        channel.bind(pageId);
        stream = new Thread(() -> {
            try {
                channel.stream(null, OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // not expected with a null stream
            }
        });
        stream.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (channel.connections() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(channel.isAlive(), "connected page should be alive");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.close();
        if (stream != null) {
            stream.join(5000);
        }
    }

    @Test
    void closedPageIsNotAlive() throws InterruptedException {
        connect("p1");
        channel.markUnload(); // pagehide without a following document request: the window was closed
        assertFalse(channel.isAlive());
    }

    @Test
    void reloadingPageStaysAliveUntilItReconnects() throws InterruptedException {
        connect("p1");
        channel.markDocumentRequest(); // the browser fetches the new document first ...
        channel.markUnload();          // ... then unloads the old one
        assertTrue(channel.isAlive(), "a page that is still loading after a reload must not count as closed");
    }

    @Test
    void reloadedPageIsAliveAgainAfterReconnecting() throws InterruptedException {
        connect("p1");
        channel.markDocumentRequest();
        channel.markUnload();
        Thread old = stream;
        connect("p2");                 // new page instance connects (replaces the old stream)
        old.join(5000);
        assertTrue(channel.isAlive());
        channel.markUnload();          // and is closed later without another document request
        assertFalse(channel.isAlive());
    }
}
