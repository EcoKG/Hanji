package io.hanji;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.hanji.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded HTTP server on 127.0.0.1. Every window has a secret token that prefixes all of its URLs:
 * <pre>
 * /{token}/{path}             page and assets from the web root (HTML gets the client runtime injected)
 * /{token}/__hanji/hanji.js   client runtime
 * /{token}/__hanji/events     Server-Sent Events: Java -&gt; page commands
 * /{token}/__hanji/send       POST: page -&gt; Java messages
 * </pre>
 */
final class BridgeServer {

    private static final String CLIENT_PREFIX = "__hanji/";
    private static final int MAX_BODY = 32 * 1024 * 1024;
    private static final Pattern HEAD_TAG = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE = Pattern.compile("<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);

    private final HanjiRuntime runtime;
    private final AtomicInteger activeStreams = new AtomicInteger();
    private HttpServer http;
    private ExecutorService executor;
    private URI baseUri;

    BridgeServer(HanjiRuntime runtime) {
        this.runtime = runtime;
    }

    void start(int port) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        http = HttpServer.create(new InetSocketAddress(loopback, port), 128);
        executor = Executors.newCachedThreadPool(HanjiRuntime.daemonFactory("Hanji-HTTP"));
        http.setExecutor(executor);
        http.createContext("/", this::handle);
        http.start();
        baseUri = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/");
    }

    URI baseUri() {
        return baseUri;
    }

    /** Stops the server after open event streams have delivered their last batch (at most ~0.5 s). */
    void stop() {
        if (http == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 500;
        while (activeStreams.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        http.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange ex) {
        try {
            route(ex);
        } catch (Throwable t) {
            Log.debug("HTTP error for " + ex.getRequestURI() + ": " + t);
            try {
                respond(ex, 500, "text/plain; charset=utf-8", "Internal error".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // connection already broken
            }
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.length() < 2) {
            notFound(ex);
            return;
        }
        int slash = path.indexOf('/', 1);
        String token = slash < 0 ? path.substring(1) : path.substring(1, slash);
        String rest = slash < 0 ? "" : path.substring(slash + 1);
        Window window = runtime.windowByToken(token);
        if (window == null) {
            notFound(ex);
            return;
        }
        if (rest.startsWith(CLIENT_PREFIX)) {
            String name = rest.substring(CLIENT_PREFIX.length());
            switch (name) {
                case "events": events(ex, window); return;
                case "send": receive(ex, window); return;
                case "hanji.js": clientResource(ex, "hanji.js", "text/javascript; charset=utf-8"); return;
                case "hanji.css": clientResource(ex, "hanji.css", "text/css; charset=utf-8"); return;
                default: notFound(ex); return;
            }
        }
        if (rest.equals(Window.INLINE_PAGE)) {
            String html = window.inlineHtml();
            if (html == null) {
                html = blankPage(window.titleText());
            }
            sendHtml(ex, window, html);
            return;
        }
        if (rest.isEmpty() || rest.endsWith("/")) {
            rest = rest + "index.html";
        }
        staticResource(ex, window, rest);
    }

    // ------------------------------------------------------------------ static content

    private void staticResource(HttpExchange ex, Window window, String rest) throws IOException {
        if (!isSafePath(rest)) {
            notFound(ex);
            return;
        }
        byte[] data = readWebResource(rest);
        if (data == null) {
            Log.warn("Resource not found: " + runtime.config.getWebRoot() + "/" + rest);
            notFound(ex);
            return;
        }
        String mime = mimeType(rest);
        if (mime.startsWith("text/html")) {
            sendHtml(ex, window, new String(data, StandardCharsets.UTF_8));
        } else {
            respond(ex, 200, mime, data);
        }
    }

    private static boolean isSafePath(String rest) {
        if (rest.indexOf('\0') >= 0 || rest.indexOf('\\') >= 0 || rest.startsWith("/")) {
            return false;
        }
        for (String segment : rest.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    private byte[] readWebResource(String rest) throws IOException {
        HanjiConfig config = runtime.config;
        String webRoot = config.getWebRoot();
        if (config.isDevMode()) {
            Path devDir = config.getDevResourceDir();
            if (devDir != null) {
                Path root = (webRoot.isEmpty() ? devDir : devDir.resolve(webRoot)).toAbsolutePath().normalize();
                Path file = root.resolve(rest).normalize();
                if (file.startsWith(root) && Files.isRegularFile(file)) {
                    return Files.readAllBytes(file);
                }
            }
        }
        String classpathName = webRoot.isEmpty() ? rest : webRoot + "/" + rest;
        try (InputStream in = runtime.resourceLoader().getResourceAsStream(classpathName)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private void clientResource(HttpExchange ex, String name, String mime) throws IOException {
        try (InputStream in = BridgeServer.class.getResourceAsStream("/io/hanji/client/" + name)) {
            if (in == null) {
                notFound(ex);
                return;
            }
            respond(ex, 200, mime, in.readAllBytes());
        }
    }

    private void sendHtml(HttpExchange ex, Window window, String html) throws IOException {
        String injected = inject(html, window.token(), runtime.config.isDevMode());
        respond(ex, 200, "text/html; charset=utf-8", injected.getBytes(StandardCharsets.UTF_8));
    }

    /** Inserts the client runtime as the first thing in {@code <head>}, so page scripts can use {@code hanji}. */
    static String inject(String html, String token, boolean dev) {
        String tags = "<script src=\"/" + token + "/__hanji/hanji.js" + (dev ? "?dev=1" : "") + "\"></script>"
                + "<link rel=\"stylesheet\" href=\"/" + token + "/__hanji/hanji.css\">";
        Matcher m = HEAD_TAG.matcher(html);
        if (m.find()) {
            return html.substring(0, m.end()) + tags + html.substring(m.end());
        }
        m = HTML_TAG.matcher(html);
        if (m.find()) {
            return html.substring(0, m.end()) + "<head>" + tags + "</head>" + html.substring(m.end());
        }
        m = DOCTYPE.matcher(html);
        if (m.find()) {
            return html.substring(0, m.end()) + tags + html.substring(m.end());
        }
        return tags + html;
    }

    private static String blankPage(String title) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + Html.escape(title) + "</title></head><body class=\"hj-app\"></body></html>";
    }

    // ------------------------------------------------------------------ bridge

    private void events(HttpExchange ex, Window window) throws IOException {
        String pid = queryParam(ex, "page");
        if (pid == null || pid.isEmpty()) {
            respond(ex, 400, "text/plain", null);
            return;
        }
        WindowChannel channel = window.channel();
        WindowChannel.Binding binding = channel.bind(pid);
        if (binding == WindowChannel.Binding.CLOSED || binding == WindowChannel.Binding.RETIRED) {
            // 204 tells EventSource to stop reconnecting
            respond(ex, 204, null, null);
            return;
        }
        if (binding == WindowChannel.Binding.RELOAD) {
            runtime.post(() -> window.onPageReplaced(pid));
        }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        activeStreams.incrementAndGet();
        try {
            channel.stream(ex.getRequestHeaders().getFirst("Last-Event-ID"), out);
        } catch (IOException disconnected) {
            Log.debug("Event stream of window '" + window.titleText() + "' disconnected");
        } finally {
            activeStreams.decrementAndGet();
            runtime.scheduleConnectionCheck(window);
        }
    }

    private void receive(HttpExchange ex, Window window) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, null, null);
            return;
        }
        String body = readBody(ex.getRequestBody());
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?>)) {
            respond(ex, 400, null, null);
            return;
        }
        Map<?, ?> envelope = (Map<?, ?>) parsed;
        String pid = envelope.get("page") instanceof String ? (String) envelope.get("page") : null;
        if (pid == null) {
            respond(ex, 400, null, null);
            return;
        }
        long seq = envelope.get("seq") instanceof Number ? ((Number) envelope.get("seq")).longValue() : 0;
        List<?> messages = envelope.get("messages") instanceof List ? (List<?>) envelope.get("messages") : List.of();
        WindowChannel channel = window.channel();

        if (seq < 0) {
            // navigator.sendBeacon on pagehide: the page may be closing (or just reloading)
            channel.markUnload();
            runtime.post(window::onUnload);
            respond(ex, 204, null, null);
            return;
        }
        WindowChannel.Binding binding = channel.bind(pid);
        if (binding == WindowChannel.Binding.CLOSED || binding == WindowChannel.Binding.RETIRED) {
            respond(ex, binding == WindowChannel.Binding.CLOSED ? 410 : 204, null, null);
            return;
        }
        if (binding == WindowChannel.Binding.RELOAD) {
            runtime.post(() -> window.onPageReplaced(pid));
        }
        if (envelope.get("ack") instanceof Number) {
            channel.ack(((Number) envelope.get("ack")).longValue());
        }
        if (channel.acceptClientSeq(seq) && !messages.isEmpty()) {
            runtime.post(() -> window.onMessages(pid, messages));
        }
        respond(ex, 204, null, null);
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BODY) {
                throw new IOException("Request body too large");
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ responses

    private static void notFound(HttpExchange ex) throws IOException {
        respond(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        Headers h = ex.getResponseHeaders();
        if (contentType != null) {
            h.set("Content-Type", contentType);
        }
        h.set("Cache-Control", "no-store");
        if (body == null || body.length == 0 || status == 204) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    static String mimeType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        String ext = dot < 0 ? "" : p.substring(dot + 1);
        switch (ext) {
            case "html": case "htm": return "text/html; charset=utf-8";
            case "css": return "text/css; charset=utf-8";
            case "js": case "mjs": return "text/javascript; charset=utf-8";
            case "json": case "map": return "application/json; charset=utf-8";
            case "txt": return "text/plain; charset=utf-8";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "avif": return "image/avif";
            case "ico": return "image/x-icon";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "wasm": return "application/wasm";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "pdf": return "application/pdf";
            case "xml": return "application/xml";
            default: return "application/octet-stream";
        }
    }
}
