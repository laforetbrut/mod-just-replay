package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the MCP server on a free port of 127.0.0.1 with a fake game bridge, and talks to it over real HTTP.
 *
 * @author vyrriox
 */
class McpHttpServerTest {
    private static final String TOKEN = McpToken.generate();
    private static final String ALLOWED = "http://localhost:6274";

    private final List<String> origins = new CopyOnWriteArrayList<>(List.of(ALLOWED));
    private final AtomicInteger slowRuns = new AtomicInteger();
    private final AtomicReference<Thread> toolThread = new AtomicReference<>();
    private GameThreadQueue queue;
    private FakeGameThread game;
    private McpHttpServer server;
    private HttpClient http;
    private URI endpoint;
    private int nextId;

    /** Stands for the game's client thread: runs the queued calls every few milliseconds unless paused. */
    private static final class FakeGameThread extends Thread {
        private final GameThreadQueue queue;
        volatile boolean paused;
        volatile boolean running = true;

        FakeGameThread(GameThreadQueue queue) {
            super("fake game thread");
            this.queue = queue;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                if (!paused) {
                    queue.runPending(5_000_000L);
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @BeforeEach
    void start() throws IOException {
        queue = new GameThreadQueue(8);
        game = new FakeGameThread(queue);
        game.start();
        McpRegistry registry = new McpRegistry()
                .add(McpTool.builder("echo")
                        .title("Echo")
                        .description("Answers its text and the thread it ran on.")
                        .input(McpSchema.object().string("text", "Text to send back.").integer("times", "Repeats.", 1, 3, 1).required("text"))
                        .readOnly()
                        .handler((args, context) -> {
                            toolThread.set(Thread.currentThread());
                            JsonObject out = new JsonObject();
                            out.addProperty("text", args.required("text").repeat(args.integer("times", 1, 1, 3)));
                            out.addProperty("thread", Thread.currentThread().getName());
                            return McpToolResult.of(out);
                        })
                        .build())
                .add(McpTool.builder("hop")
                        .description("Runs on the network thread and asks the game thread for one value.")
                        .offGameThread()
                        .handler((args, context) -> {
                            JsonObject out = new JsonObject();
                            out.addProperty("network", Thread.currentThread().getName());
                            out.addProperty("game", context.onGameThread(() -> Thread.currentThread().getName()));
                            return McpToolResult.of(out);
                        })
                        .build())
                .add(McpTool.builder("slow")
                        .description("Counts its runs.")
                        .handler((args, context) -> {
                            slowRuns.incrementAndGet();
                            return McpToolResult.of(new JsonObject());
                        })
                        .build())
                .add(McpTool.builder("boom")
                        .description("Always fails.")
                        .handler((args, context) -> {
                            throw new IllegalStateException("the game is on fire");
                        })
                        .build())
                .add(new McpResource("test://state", "state", "State", "A fixed text.", "application/json", true,
                        context -> "{\"thread\":\"" + Thread.currentThread().getName() + "\"}"))
                .add(new McpResource.Template("test://items/{id}", "item", "Item", "Items a and b.", "text/plain", false,
                        (id, context) -> id.equals("a") || id.equals("b") ? "item " + id : null));
        McpProtocol protocol = new McpProtocol(registry, queue, new McpProtocol.Info("justreplay", "Just Replay", "9.9.9", "Test server."),
                () -> 300L);
        server = new McpHttpServer(protocol, TOKEN, () -> origins);
        server.start(0);
        endpoint = URI.create("http://127.0.0.1:" + server.port() + McpHttpServer.PATH);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        server.stop();
        game.running = false;
        game.interrupt();
        queue.close();
    }

    // ------------------------------------------------------------------ helpers

    private String request(String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", ++nextId);
        o.addProperty("method", method);
        if (params != null) {
            o.add("params", params);
        }
        return o.toString();
    }

    /** Params of a modern request: the version and the client's capabilities in {@code _meta}. */
    private static JsonObject modernParams(JsonObject params) {
        JsonObject p = params == null ? new JsonObject() : params;
        JsonObject meta = new JsonObject();
        meta.addProperty(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.LATEST);
        meta.add(McpProtocol.META_CLIENT_CAPABILITIES, new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "test-client");
        info.addProperty("version", "1.0");
        meta.add(McpProtocol.META_CLIENT_INFO, info);
        p.add("_meta", meta);
        return p;
    }

    private static JsonObject callParams(String tool, JsonObject arguments) {
        JsonObject p = new JsonObject();
        p.addProperty("name", tool);
        if (arguments != null) {
            p.add("arguments", arguments);
        }
        return p;
    }

    private HttpResponse<String> post(String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        boolean auth = true;
        for (int i = 0; i + 1 < headers.length; i += 2) {
            if (headers[i].equals("Authorization") && headers[i + 1] == null) {
                auth = false;
            } else {
                b.setHeader(headers[i], headers[i + 1]);
                auth &= !headers[i].equals("Authorization");
            }
        }
        if (auth) {
            b.header("Authorization", "Bearer " + TOKEN);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> modern(String method, JsonObject params, String name) throws Exception {
        List<String> h = new ArrayList<>(List.of("MCP-Protocol-Version", McpProtocol.LATEST, "Mcp-Method", method));
        if (name != null) {
            h.add("Mcp-Name");
            h.add(name);
        }
        return post(request(method, modernParams(params)), h.toArray(new String[0]));
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject result(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        JsonObject o = json(response);
        assertTrue(o.has("result"), response.body());
        return o.getAsJsonObject("result");
    }

    private static int errorCode(HttpResponse<String> response) {
        return json(response).getAsJsonObject("error").get("code").getAsInt();
    }

    // ------------------------------------------------------------------ handshake and lists

    @Test
    void listensOnLoopbackOnly() {
        assertTrue(server.port() > 0);
        assertTrue(server.isRunning());
    }

    @Test
    void initializeNegotiatesALegacyVersion() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2025-06-18");
        params.add("capabilities", new JsonObject());
        JsonObject result = result(post(request("initialize", params)));
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertEquals("justreplay", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertEquals("Just Replay", result.getAsJsonObject("serverInfo").get("title").getAsString());
        assertEquals("9.9.9", result.getAsJsonObject("serverInfo").get("version").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertTrue(result.getAsJsonObject("capabilities").has("resources"));
        assertEquals("Test server.", result.get("instructions").getAsString());

        // An older or unknown version gets the newest legacy one; the client decides what to do.
        params.addProperty("protocolVersion", "2024-11-05");
        assertEquals("2025-11-25", result(post(request("initialize", params))).get("protocolVersion").getAsString());
    }

    @Test
    void notificationsAreAcceptedWithoutBody() throws Exception {
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void toolsListGivesTheToolsInOrder() throws Exception {
        JsonArray tools = result(post(request("tools/list", null), "MCP-Protocol-Version", "2025-11-25")).getAsJsonArray("tools");
        assertEquals(4, tools.size());
        JsonObject echo = tools.get(0).getAsJsonObject();
        assertEquals("echo", echo.get("name").getAsString());
        assertEquals("Echo", echo.get("title").getAsString());
        JsonObject schema = echo.getAsJsonObject("inputSchema");
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertEquals("text", schema.getAsJsonArray("required").get(0).getAsString());
        assertTrue(echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        assertEquals("hop", tools.get(1).getAsJsonObject().get("name").getAsString());
        // Legacy answers carry no field of the modern revision.
        assertNull(result(post(request("tools/list", null))).get("resultType"));
    }

    @Test
    void modernRequestsNeedNoHandshake() throws Exception {
        JsonObject discover = result(modern("server/discover", null, null));
        assertEquals("complete", discover.get("resultType").getAsString());
        JsonArray versions = discover.getAsJsonArray("supportedVersions");
        assertEquals("2026-07-28", versions.get(0).getAsString());
        assertTrue(versions.toString().contains("2025-11-25"));
        assertEquals("justreplay", discover.getAsJsonObject("_meta").getAsJsonObject(McpProtocol.META_SERVER_INFO).get("name").getAsString());

        JsonObject list = result(modern("tools/list", null, null));
        assertEquals("complete", list.get("resultType").getAsString());
        assertEquals(4, list.getAsJsonArray("tools").size());
        assertTrue(list.get("ttlMs").getAsLong() > 0);
        assertEquals("private", list.get("cacheScope").getAsString());

        JsonObject args = new JsonObject();
        args.addProperty("text", "hi");
        JsonObject call = result(modern("tools/call", callParams("echo", args), "echo"));
        assertEquals("complete", call.get("resultType").getAsString());
        assertEquals("hi", call.getAsJsonObject("structuredContent").get("text").getAsString());
    }

    // ------------------------------------------------------------------ the bridge to the game thread

    @Test
    void toolsCallRunsOnTheGameThread() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("text", "ab");
        args.addProperty("times", 2);
        JsonObject result = result(post(request("tools/call", callParams("echo", args))));
        assertFalse(result.get("isError").getAsBoolean());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertEquals("abab", structured.get("text").getAsString());
        assertEquals("fake game thread", structured.get("thread").getAsString());
        assertEquals(game, toolThread.get());
        // The same JSON as text, for clients that only read text.
        JsonArray content = result.getAsJsonArray("content");
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(structured, JsonParser.parseString(content.get(0).getAsJsonObject().get("text").getAsString()));
    }

    @Test
    void anOffThreadToolReachesTheGameThroughTheQueue() throws Exception {
        JsonObject structured = result(post(request("tools/call", callParams("hop", null)))).getAsJsonObject("structuredContent");
        assertEquals("fake game thread", structured.get("game").getAsString());
        assertNotEquals("fake game thread", structured.get("network").getAsString());
    }

    @Test
    void aFrozenGameGivesAToolErrorAndTheCallNeverRunsLater() throws Exception {
        game.paused = true;
        long start = System.nanoTime();
        JsonObject result = result(post(request("tools/call", callParams("slow", null))));
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("did not answer"));
        assertTrue(millis >= 250 && millis < 5000, "answered after " + millis + " ms");
        game.paused = false;
        Thread.sleep(100);
        assertEquals(0, slowRuns.get(), "a call whose caller gave up must not run");
        assertFalse(result(post(request("tools/call", callParams("slow", null)))).get("isError").getAsBoolean());
        assertEquals(1, slowRuns.get());
    }

    @Test
    void toolProblemsAreToolErrorsAndUnknownToolsAreProtocolErrors() throws Exception {
        JsonObject missing = result(post(request("tools/call", callParams("echo", new JsonObject()))));
        assertTrue(missing.get("isError").getAsBoolean());
        assertTrue(missing.toString().contains("'text' is required"), missing.toString());

        JsonObject args = new JsonObject();
        args.addProperty("text", "x");
        args.addProperty("colour", "red");
        assertTrue(result(post(request("tools/call", callParams("echo", args)))).toString().contains("Unknown argument 'colour'"));

        JsonObject boom = result(post(request("tools/call", callParams("boom", null))));
        assertTrue(boom.get("isError").getAsBoolean());
        assertTrue(boom.toString().contains("the game is on fire"));

        HttpResponse<String> unknown = post(request("tools/call", callParams("nope", null)));
        assertEquals(200, unknown.statusCode());
        assertEquals(McpException.INVALID_PARAMS, errorCode(unknown));
        assertEquals(nextId, json(unknown).get("id").getAsInt());
    }

    // ------------------------------------------------------------------ resources

    @Test
    void resourcesListAndRead() throws Exception {
        JsonObject list = result(post(request("resources/list", null)));
        assertEquals("test://state", list.getAsJsonArray("resources").get(0).getAsJsonObject().get("uri").getAsString());
        JsonObject templates = result(post(request("resources/templates/list", null)));
        assertEquals("test://items/{id}", templates.getAsJsonArray("resourceTemplates").get(0).getAsJsonObject().get("uriTemplate").getAsString());

        JsonObject uri = new JsonObject();
        uri.addProperty("uri", "test://state");
        JsonObject content = result(post(request("resources/read", uri))).getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("application/json", content.get("mimeType").getAsString());
        assertEquals("fake game thread", JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject().get("thread").getAsString());

        uri.addProperty("uri", "test://items/b");
        assertEquals("item b", result(post(request("resources/read", uri))).getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString());

        uri.addProperty("uri", "test://items/zz");
        assertEquals(McpException.RESOURCE_NOT_FOUND_LEGACY, errorCode(post(request("resources/read", uri))));
        HttpResponse<String> modernMissing = modern("resources/read", uri, "test://items/zz");
        assertEquals(McpException.INVALID_PARAMS, errorCode(modernMissing));
        JsonObject read = result(modern("resources/read", jsonUri("test://items/a"), "test://items/a"));
        assertEquals(0, read.get("ttlMs").getAsLong());
    }

    private static JsonObject jsonUri(String uri) {
        JsonObject o = new JsonObject();
        o.addProperty("uri", uri);
        return o;
    }

    // ------------------------------------------------------------------ security

    @Test
    void requestsWithoutTheTokenAreRefused() throws Exception {
        HttpResponse<String> none = post(request("tools/list", null), "Authorization", null);
        assertEquals(401, none.statusCode());
        assertTrue(none.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        assertEquals(McpException.INVALID_REQUEST, errorCode(none));
        assertFalse(json(none).has("result"));

        assertEquals(401, post(request("tools/list", null), "Authorization", "Bearer " + McpToken.generate()).statusCode());
        assertEquals(401, post(request("tools/list", null), "Authorization", TOKEN).statusCode());
        assertEquals(401, post(request("tools/list", null), "Authorization", "Basic " + TOKEN).statusCode());
        assertEquals(200, post(request("tools/list", null), "Authorization", "bearer " + TOKEN).statusCode());
        assertEquals(0, slowRuns.get());
    }

    @Test
    void foreignOriginsAreRefused() throws Exception {
        HttpResponse<String> evil = post(request("tools/call", callParams("slow", null)), "Origin", "https://evil.example");
        assertEquals(403, evil.statusCode());
        assertFalse(evil.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertEquals(0, slowRuns.get(), "a refused request must not reach the game");
        assertEquals(403, post(request("tools/list", null), "Origin", "null").statusCode());
        // A page on another port of this machine is foreign too, unless the settings allow it.
        assertEquals(403, post(request("tools/list", null), "Origin", "http://localhost:3000").statusCode());

        HttpResponse<String> allowed = post(request("tools/list", null), "Origin", ALLOWED);
        assertEquals(200, allowed.statusCode());
        assertEquals(ALLOWED, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals(200, post(request("tools/list", null), "Origin", "http://127.0.0.1:" + server.port()).statusCode());

        // The list is read at each request: removing the origin refuses it at once.
        origins.clear();
        assertEquals(403, post(request("tools/list", null), "Origin", ALLOWED).statusCode());
    }

    @Test
    void foreignHostsAreRefused() throws Exception {
        assertEquals(403, rawStatus("evil.example:" + server.port()));
        assertEquals(403, rawStatus("127.0.0.1.evil.example"));
        assertEquals(200, rawStatus("localhost:" + server.port()));
        assertEquals(200, rawStatus("127.0.0.1:" + server.port()));
    }

    /** Status of a POST sent by hand, with any Host header. */
    private int rawStatus(String host) throws IOException {
        byte[] body = request("tools/list", null).getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String head = "POST " + McpHttpServer.PATH + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                line.append((char) c);
            }
            return Integer.parseInt(line.toString().split(" ")[1]);
        }
    }

    @Test
    void streamsAndSessionsAreNotOffered() throws Exception {
        HttpRequest get = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + TOKEN)
                .header("Accept", "text/event-stream").GET().build();
        assertEquals(405, http.send(get, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest delete = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + TOKEN).DELETE().build();
        assertEquals(405, http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest other = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/other"))
                .header("Authorization", "Bearer " + TOKEN).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(404, http.send(other, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> init = post(request("initialize", new JsonObject()));
        assertFalse(init.headers().firstValue("Mcp-Session-Id").isPresent());
    }

    // ------------------------------------------------------------------ malformed and modern rules

    @Test
    void malformedMessagesGetJsonRpcErrors() throws Exception {
        HttpResponse<String> parse = post("{not json");
        assertEquals(400, parse.statusCode());
        assertEquals(McpException.PARSE_ERROR, errorCode(parse));
        HttpResponse<String> batch = post("[" + request("tools/list", null) + "]");
        assertEquals(400, batch.statusCode());
        assertEquals(McpException.INVALID_REQUEST, errorCode(batch));
        assertEquals(McpException.INVALID_REQUEST, errorCode(post("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}")));
        assertEquals(McpException.METHOD_NOT_FOUND, errorCode(post(request("nothing/here", null))));
        assertEquals(415, post(request("tools/list", null), "Content-Type", "text/plain").statusCode());
        JsonObject ping = result(post(request("ping", null)));
        assertEquals(0, ping.size());
    }

    @Test
    void modernRequestsAreCheckedAgainstTheirHeaders() throws Exception {
        String body = request("tools/list", modernParams(null));
        HttpResponse<String> noHeaders = post(body);
        assertEquals(400, noHeaders.statusCode());
        assertEquals(McpException.HEADER_MISMATCH, errorCode(noHeaders));

        HttpResponse<String> wrongMethod = post(body, "MCP-Protocol-Version", McpProtocol.LATEST, "Mcp-Method", "tools/call");
        assertEquals(McpException.HEADER_MISMATCH, errorCode(wrongMethod));

        JsonObject args = new JsonObject();
        args.addProperty("text", "x");
        String call = request("tools/call", modernParams(callParams("echo", args)));
        assertEquals(McpException.HEADER_MISMATCH, errorCode(post(call, "MCP-Protocol-Version", McpProtocol.LATEST, "Mcp-Method", "tools/call")));
        assertEquals(McpException.HEADER_MISMATCH, errorCode(post(call, "MCP-Protocol-Version", McpProtocol.LATEST, "Mcp-Method", "tools/call",
                "Mcp-Name", "slow")));
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString("echo".getBytes(StandardCharsets.UTF_8)) + "?=";
        assertEquals(200, post(call, "MCP-Protocol-Version", McpProtocol.LATEST, "Mcp-Method", "tools/call", "Mcp-Name", encoded).statusCode());
        assertEquals(McpException.HEADER_MISMATCH, errorCode(post(call, "MCP-Protocol-Version", "2025-11-25", "Mcp-Method", "tools/call",
                "Mcp-Name", "echo")));

        JsonObject old = modernParams(null);
        old.getAsJsonObject("_meta").addProperty(McpProtocol.META_PROTOCOL_VERSION, "1900-01-01");
        HttpResponse<String> unsupported = post(request("tools/list", old), "MCP-Protocol-Version", "1900-01-01", "Mcp-Method", "tools/list");
        assertEquals(400, unsupported.statusCode());
        assertEquals(McpException.UNSUPPORTED_PROTOCOL_VERSION, errorCode(unsupported));
        JsonElement supported = json(unsupported).getAsJsonObject("error").getAsJsonObject("data").get("supported");
        assertTrue(supported.toString().contains(McpProtocol.LATEST));

        HttpResponse<String> notFound = modern("nothing/here", null, null);
        assertEquals(404, notFound.statusCode());
        assertEquals(McpException.METHOD_NOT_FOUND, errorCode(notFound));

        JsonObject noCapabilities = modernParams(null);
        noCapabilities.getAsJsonObject("_meta").remove(McpProtocol.META_CLIENT_CAPABILITIES);
        HttpResponse<String> missing = post(request("tools/list", noCapabilities), "MCP-Protocol-Version", McpProtocol.LATEST,
                "Mcp-Method", "tools/list");
        assertEquals(400, missing.statusCode());
        assertEquals(McpException.INVALID_PARAMS, errorCode(missing));

        // A modern header without the _meta of the body is a broken modern request, not a legacy one.
        HttpResponse<String> noMeta = post(request("tools/list", null), "MCP-Protocol-Version", McpProtocol.LATEST);
        assertEquals(400, noMeta.statusCode());
        assertEquals(McpException.INVALID_PARAMS, errorCode(noMeta));
        assertEquals(McpException.UNSUPPORTED_PROTOCOL_VERSION, errorCode(post(request("tools/list", null), "MCP-Protocol-Version", "1999-01-01")));
    }

    @Test
    void stoppingClosesThePort() throws Exception {
        int port = server.port();
        server.stop();
        assertEquals(-1, server.port());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            throw new AssertionError("the port is still open");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    void localHostAndOriginRules() {
        assertTrue(McpHttpServer.isLocalHost("127.0.0.1:24464"));
        assertTrue(McpHttpServer.isLocalHost("LOCALHOST"));
        assertTrue(McpHttpServer.isLocalHost("[::1]:80"));
        assertFalse(McpHttpServer.isLocalHost("localhost.evil.example"));
        assertFalse(McpHttpServer.isLocalHost("127.0.0.1:80x"));
        assertFalse(McpHttpServer.isLocalHost("[::2]"));
        assertEquals("http://localhost:6274", McpHttpServer.normalizeOrigin("HTTP://LocalHost:6274/"));
        assertNull(McpHttpServer.normalizeOrigin("null"));
        assertNull(McpHttpServer.normalizeOrigin("http://localhost:6274/path"));
        assertTrue(McpHttpServer.acceptsJson(null));
        assertTrue(McpHttpServer.acceptsJson(List.of("application/json, text/event-stream")));
        assertTrue(McpHttpServer.acceptsJson(List.of("*/*")));
        assertFalse(McpHttpServer.acceptsJson(List.of("text/event-stream")));
    }
}
