package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vyrriox.justreplay.JustReplayCore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The Streamable HTTP transport of the MCP server, on the HTTP server of the JDK: one endpoint ({@code /mcp}) on
 * 127.0.0.1 only. Before a message reaches {@link McpProtocol}, every request must
 * <ul>
 *     <li>name a local host ({@code 127.0.0.1}, {@code localhost} or {@code [::1]}), against DNS rebinding;</li>
 *     <li>come with no {@code Origin} (desktop and command line clients) or an allowed one: web pages get 403;</li>
 *     <li>carry the access token as {@code Authorization: Bearer <token>}: without it, 401.</li>
 * </ul>
 * Answers are single JSON objects (no event streams): GET and DELETE get 405, as the specification allows.
 *
 * @author vyrriox
 */
public final class McpHttpServer {
    public static final String PATH = "/mcp";
    /** Largest request body read; MCP requests are a few hundred bytes. */
    public static final int MAX_BODY = 1 << 20;
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final String ALLOWED_HEADERS = "Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name, "
            + "Mcp-Session-Id, Last-Event-ID";
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final McpProtocol protocol;
    private final String token;
    private final Supplier<List<String>> allowedOrigins;
    private HttpServer server;
    private ExecutorService executor;
    private volatile int port = -1;

    /**
     * @param token          the access token every request must carry
     * @param allowedOrigins origins of web pages allowed to call the server, read at each request
     */
    public McpHttpServer(McpProtocol protocol, String token, Supplier<List<String>> allowedOrigins) {
        if (!McpToken.usable(token)) {
            throw new IllegalArgumentException("The MCP server needs a token of " + McpToken.MIN_LENGTH + " characters or more");
        }
        this.protocol = protocol;
        this.token = token;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Listens on 127.0.0.1:{@code port} (0 picks a free port, see {@link #port()}). The threads of the server are
     * daemon threads: they never keep the game running after it quits.
     *
     * @throws IOException when the port cannot be used (taken by another program, for instance)
     */
    public synchronized void start(int port) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Already started");
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), port), 16);
        ExecutorService pool = Executors.newFixedThreadPool(4, daemon("Just Replay MCP"));
        created.setExecutor(pool);
        created.createContext("/", this::exchange);
        // The JDK's dispatcher thread takes the daemon flag of the thread that starts it.
        IOException[] failure = new IOException[1];
        Thread starter = new Thread(() -> {
            try {
                created.start();
            } catch (RuntimeException e) {
                failure[0] = new IOException(McpProtocol.describe(e), e);
            }
        }, "Just Replay MCP start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] != null) {
            created.stop(0);
            pool.shutdownNow();
            throw failure[0];
        }
        this.server = created;
        this.executor = pool;
        this.port = created.getAddress().getPort();
    }

    /** The port listened on, or -1 when stopped. */
    public int port() {
        return port;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** Stops listening and closes the open connections. */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        server = null;
        executor = null;
        port = -1;
    }

    // ------------------------------------------------------------------ requests

    private void exchange(HttpExchange ex) {
        try {
            handle(ex);
        } catch (Throwable t) {
            JustReplayCore.LOGGER.warn("MCP request failed", t);
            try {
                send(ex, 500, McpProtocol.error(null, new McpException(McpException.INTERNAL_ERROR, "Internal error", 500)).body(), null);
            } catch (IOException | RuntimeException ignored) {
                // The connection is gone; nothing else to do.
            }
        } finally {
            ex.close();
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        Headers in = ex.getRequestHeaders();
        String host = in.getFirst("Host");
        if (host != null && !isLocalHost(host)) {
            sendError(ex, 403, McpException.INVALID_REQUEST, "Forbidden: this server only answers requests sent to 127.0.0.1 or localhost", null);
            return;
        }
        String origin = in.getFirst("Origin");
        if (origin != null && !isAllowedOrigin(origin)) {
            sendError(ex, 403, McpException.INVALID_REQUEST, "Forbidden: origin " + origin + " is not allowed to call this server", null);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!PATH.equals(path) && !(PATH + "/").equals(path)) {
            sendError(ex, 404, McpException.INVALID_REQUEST, "Not found: the MCP endpoint is " + PATH, origin);
            return;
        }
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if ("OPTIONS".equals(method)) {
            // A browser's preflight, for an allowed origin: it carries no token.
            Headers out = ex.getResponseHeaders();
            cors(out, origin);
            out.set("Access-Control-Allow-Methods", "POST, OPTIONS");
            out.set("Access-Control-Allow-Headers", ALLOWED_HEADERS);
            out.set("Access-Control-Max-Age", "600");
            out.set("Allow", "POST, OPTIONS");
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (!McpToken.matches(token, in.getFirst("Authorization"))) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"Just Replay\"");
            sendError(ex, 401, McpException.INVALID_REQUEST,
                    "Unauthorized: send the token of the Just Replay settings as 'Authorization: Bearer <token>'", origin);
            return;
        }
        if (!"POST".equals(method)) {
            // No event stream to open (GET) and no session to end (DELETE).
            ex.getResponseHeaders().set("Allow", "POST, OPTIONS");
            sendError(ex, 405, McpException.INVALID_REQUEST, "Method not allowed: send JSON-RPC messages with POST", origin);
            return;
        }
        String contentType = in.getFirst("Content-Type");
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            sendError(ex, 415, McpException.INVALID_REQUEST, "Unsupported media type: the body must be application/json", origin);
            return;
        }
        if (!acceptsJson(in.get("Accept"))) {
            sendError(ex, 406, McpException.INVALID_REQUEST, "Not acceptable: this server answers with application/json", origin);
            return;
        }
        String body = readBody(ex.getRequestBody());
        if (body == null) {
            sendError(ex, 413, McpException.INVALID_REQUEST, "Request too large: " + MAX_BODY + " bytes at most", origin);
            return;
        }
        McpProtocol.Reply reply = protocol.handle(body, in::getFirst);
        send(ex, reply.status(), reply.body(), origin);
    }

    private void sendError(HttpExchange ex, int status, int code, String message, String origin) throws IOException {
        send(ex, status, McpProtocol.error(null, new McpException(code, message, status)).body(), origin);
    }

    private void send(HttpExchange ex, int status, JsonObject body, String origin) throws IOException {
        Headers out = ex.getResponseHeaders();
        out.set("Cache-Control", "no-store");
        cors(out, origin);
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = McpToolResult.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        out.set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void cors(Headers out, String origin) {
        if (origin != null && isAllowedOrigin(origin)) {
            out.set("Access-Control-Allow-Origin", origin);
            out.set("Vary", "Origin");
        }
    }

    /** The body as UTF-8 text, or null when it is larger than {@link #MAX_BODY}. */
    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > MAX_BODY) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ checks

    /** A Host header naming this machine: 127.0.0.1, localhost or [::1], with or without a port. */
    static boolean isLocalHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close < 0) {
                return false;
            }
            String rest = h.substring(close + 1);
            return h.substring(0, close + 1).equals("[::1]") && (rest.isEmpty() || isPort(rest));
        }
        int colon = h.indexOf(':');
        String name = colon < 0 ? h : h.substring(0, colon);
        if (colon >= 0 && !isPort(h.substring(colon))) {
            return false;
        }
        return name.equals("127.0.0.1") || name.equals("localhost");
    }

    private static boolean isPort(String colonAndDigits) {
        if (colonAndDigits.length() < 2 || colonAndDigits.charAt(0) != ':') {
            return false;
        }
        for (int i = 1; i < colonAndDigits.length(); i++) {
            if (!Character.isDigit(colonAndDigits.charAt(i))) {
                return false;
            }
        }
        return colonAndDigits.length() <= 6;
    }

    /** The origin of this server itself, or one of the allowed origins of the settings. */
    boolean isAllowedOrigin(String origin) {
        String o = normalizeOrigin(origin);
        if (o == null) {
            return false;
        }
        int p = port;
        if (p > 0 && (o.equals("http://127.0.0.1:" + p) || o.equals("http://localhost:" + p))) {
            return true;
        }
        List<String> allowed = allowedOrigins == null ? List.of() : allowedOrigins.get();
        for (String a : allowed == null ? List.<String>of() : allowed) {
            String n = normalizeOrigin(a);
            if (n != null && n.equals(o)) {
                return true;
            }
        }
        return false;
    }

    /** {@code scheme://host[:port]} in lower case without a trailing slash, or null when it is not an origin. */
    static String normalizeOrigin(String origin) {
        if (origin == null) {
            return null;
        }
        String o = origin.trim();
        while (o.endsWith("/")) {
            o = o.substring(0, o.length() - 1);
        }
        try {
            URI uri = new URI(o);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                return null;
            }
            String s = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
            return uri.getPort() >= 0 ? s + ":" + uri.getPort() : s;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** No Accept header, or one that takes JSON (application/json, application/*, or anything). */
    static boolean acceptsJson(List<String> accept) {
        if (accept == null || accept.isEmpty()) {
            return true;
        }
        List<String> types = new ArrayList<>();
        for (String line : accept) {
            for (String part : line.split(",")) {
                String type = part.trim().toLowerCase(Locale.ROOT);
                int semi = type.indexOf(';');
                types.add(semi < 0 ? type : type.substring(0, semi).trim());
            }
        }
        return types.contains("application/json") || types.contains("application/*") || types.contains("*/*") || types.contains("");
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name + " #" + THREADS.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
