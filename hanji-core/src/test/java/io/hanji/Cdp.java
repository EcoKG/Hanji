package io.hanji;

import io.hanji.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal Chrome DevTools Protocol client (JDK WebSocket) used to drive a headless browser in tests. */
final class Cdp implements AutoCloseable {

    private final WebSocket socket;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    Cdp(String webSocketUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        socket = client.newWebSocketBuilder()
                .buildAsync(URI.create(webSocketUrl), new Receiver())
                .get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> send(String method, Map<String, Object> params) throws Exception {
        long id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
        pending.put(id, response);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        socket.sendText(Json.stringify(msg), true).get(10, TimeUnit.SECONDS);
        Map<String, Object> r = response.get(30, TimeUnit.SECONDS);
        if (r.containsKey("error")) {
            throw new IllegalStateException("CDP " + method + " failed: " + r.get("error"));
        }
        return (Map<String, Object>) r.get("result");
    }

    /** Evaluates an expression in the page (promises are awaited) and returns its JSON value. */
    @SuppressWarnings("unchecked")
    Object eval(String expression) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", expression);
        params.put("awaitPromise", true);
        params.put("returnByValue", true);
        Map<String, Object> r = send("Runtime.evaluate", params);
        if (r.containsKey("exceptionDetails")) {
            throw new AssertionError("JavaScript exception: " + r.get("exceptionDetails"));
        }
        return ((Map<String, Object>) r.get("result")).get("value");
    }

    /** Polls until the JavaScript condition is truthy. */
    void waitFor(String condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Object last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = eval("!!(" + condition + ")");
                if (Boolean.TRUE.equals(last)) {
                    return;
                }
            } catch (AssertionError | IllegalStateException e) {
                last = e.getMessage(); // page may be navigating
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + condition + " (last: " + last + ")");
    }

    /** Sends a command without waiting for the answer (e.g. {@code Browser.close}, which never answers). */
    void fire(String method) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", ids.incrementAndGet());
        msg.put("method", method);
        msg.put("params", Map.of());
        socket.sendText(Json.stringify(msg), true);
    }

    /** WebSocket URL of the first DevTools target on {@code port} whose type and URL match. */
    @SuppressWarnings("unchecked")
    static String targetUrl(int port, String type, String urlPart) throws Exception {
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            try {
                if ("browser".equals(type)) {
                    String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/version")).build(),
                            HttpResponse.BodyHandlers.ofString()).body();
                    return (String) ((Map<String, Object>) Json.parse(body)).get("webSocketDebuggerUrl");
                }
                String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                for (Object t : (List<Object>) Json.parse(body)) {
                    Map<String, Object> target = (Map<String, Object>) t;
                    if (type.equals(target.get("type")) && String.valueOf(target.get("url")).contains(urlPart)) {
                        return (String) target.get("webSocketDebuggerUrl");
                    }
                }
            } catch (java.io.IOException notYetListening) {
                // browser still starting
            }
            if (System.currentTimeMillis() > deadline) {
                throw new java.io.IOException("No DevTools target '" + type + "' matching '" + urlPart + "' on port " + port);
            }
            Thread.sleep(100);
        }
    }

    @Override
    public void close() {
        socket.abort();
    }

    private final class Receiver implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        @SuppressWarnings("unchecked")
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                Map<String, Object> m = (Map<String, Object>) Json.parse(buffer.toString());
                buffer.setLength(0);
                if (m.get("id") instanceof Number) {
                    CompletableFuture<Map<String, Object>> f = pending.remove(((Number) m.get("id")).longValue());
                    if (f != null) {
                        f.complete(m);
                    }
                }
            }
            ws.request(1);
            return null;
        }
    }
}
