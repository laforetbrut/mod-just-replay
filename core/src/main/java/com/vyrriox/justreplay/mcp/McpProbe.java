package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A small MCP client for the in-game self test: asks a running server over local HTTP what a real client would (the
 * legacy handshake, the modern discovery, the tool list, the read tools, a resource, two refusals) and turns each
 * answer into a log line ending in {@code -> OK} or {@code -> FAIL}. It runs on its own thread: the tools need the
 * game thread to answer, so the game must keep ticking meanwhile.
 *
 * @author vyrriox
 */
public final class McpProbe {
    /** Tools the server must list, in this order. */
    public static final List<String> TOOLS = List.of("get_state", "list_replays", "get_project", "list_markers", "get_marker");

    /**
     * What the answers must say about the replay open in the game.
     *
     * @param replayId   id of the open replay
     * @param markers    how many markers its project holds
     * @param markerId   id of one of them
     * @param markerName its name
     * @param markerNote its note
     */
    public record Expect(String replayId, int markers, String markerId, String markerName, String markerNote) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final String token;
    private int nextId;

    private McpProbe(int port, String token) {
        this.endpoint = URI.create("http://127.0.0.1:" + port + McpHttpServer.PATH);
        this.token = token;
    }

    /** Runs the checks on a new daemon thread; the future holds one line per check. */
    public static CompletableFuture<List<String>> start(int port, String token, Expect expect) {
        CompletableFuture<List<String>> lines = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                lines.complete(new McpProbe(port, token).run(expect));
            } catch (Throwable t) {
                lines.complete(List.of("mcp probe stopped: " + McpProtocol.describe(t) + " -> FAIL"));
            }
        }, "Just Replay MCP probe");
        thread.setDaemon(true);
        thread.start();
        return lines;
    }

    List<String> run(Expect expect) {
        List<String> lines = new ArrayList<>();
        check(lines, "mcp initialize (legacy handshake)", () -> {
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", "2025-11-25");
            params.add("capabilities", new JsonObject());
            JsonObject info = new JsonObject();
            info.addProperty("name", "justreplay-selftest");
            info.addProperty("version", "1");
            params.add("clientInfo", info);
            JsonObject r = result(send("initialize", params, false, null, true));
            String version = r.get("protocolVersion").getAsString();
            JsonObject server = r.getAsJsonObject("serverInfo");
            return verdict("protocol " + version + ", server " + server.get("name").getAsString() + " " + server.get("version").getAsString(),
                    version.equals("2025-11-25") && server.get("name").getAsString().equals("justreplay"));
        });
        check(lines, "mcp server/discover (" + McpProtocol.LATEST + ", no handshake)", () -> {
            JsonObject r = result(send("server/discover", new JsonObject(), true, null, true));
            JsonArray versions = r.getAsJsonArray("supportedVersions");
            return verdict("versions " + versions, "complete".equals(r.get("resultType").getAsString())
                    && versions.get(0).getAsString().equals(McpProtocol.LATEST));
        });
        check(lines, "mcp tools/list", () -> {
            JsonArray tools = result(send("tools/list", new JsonObject(), true, null, true)).getAsJsonArray("tools");
            List<String> names = new ArrayList<>();
            for (JsonElement tool : tools) {
                names.add(tool.getAsJsonObject().get("name").getAsString());
            }
            return verdict(names.toString(), names.equals(TOOLS));
        });
        check(lines, "mcp get_state over HTTP", () -> {
            JsonObject replay = tool("get_state", new JsonObject(), true).getAsJsonObject("replay");
            return verdict("replay " + replay.get("replayId").getAsString() + " open=" + replay.get("open").getAsBoolean() + " at "
                            + replay.get("time").getAsString(),
                    replay.get("open").getAsBoolean() && replay.get("replayId").getAsString().equals(expect.replayId()));
        });
        check(lines, "mcp list_markers", () -> {
            JsonObject list = tool("list_markers", new JsonObject(), false);
            String note = null;
            for (JsonElement m : list.getAsJsonArray("markers")) {
                if (m.getAsJsonObject().get("id").getAsString().equals(expect.markerId())) {
                    note = m.getAsJsonObject().get("note").getAsString();
                }
            }
            return verdict(list.get("count").getAsInt() + " markers (expected " + expect.markers() + "), note of '" + expect.markerName()
                    + "' read back: " + expect.markerNote().equals(note), list.get("count").getAsInt() == expect.markers()
                    && expect.markerNote().equals(note));
        });
        check(lines, "mcp get_marker", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", expect.markerId());
            JsonObject marker = tool("get_marker", args, true).getAsJsonObject("marker");
            return verdict("'" + marker.get("name").getAsString() + "' at " + marker.get("time").getAsString(),
                    marker.get("name").getAsString().equals(expect.markerName()) && marker.get("note").getAsString().equals(expect.markerNote()));
        });
        check(lines, "mcp get_project", () -> {
            JsonObject args = new JsonObject();
            JsonArray sections = new JsonArray();
            sections.add("overview");
            args.add("sections", sections);
            JsonObject overview = tool("get_project", args, false).getAsJsonObject("overview");
            return verdict(overview.get("markers").getAsInt() + " markers, " + overview.get("cameras").getAsInt() + " cameras in the overview",
                    overview.get("markers").getAsInt() == expect.markers());
        });
        check(lines, "mcp resources/read " + McpReadTools.STATE_URI, () -> {
            JsonObject params = new JsonObject();
            params.addProperty("uri", McpReadTools.STATE_URI);
            JsonObject content = result(send("resources/read", params, true, McpReadTools.STATE_URI, true))
                    .getAsJsonArray("contents").get(0).getAsJsonObject();
            boolean open = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject().getAsJsonObject("replay").get("open").getAsBoolean();
            return verdict(content.get("mimeType").getAsString() + ", replay open=" + open, open);
        });
        check(lines, "mcp request without the token refused", () -> {
            int status = send("tools/list", new JsonObject(), false, null, false).statusCode();
            return verdict("HTTP " + status, status == 401);
        });
        check(lines, "mcp request from a foreign web page refused", () -> {
            int status = http.send(post("tools/list", new JsonObject(), false, null, true).header("Origin", "https://evil.example").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
            return verdict("HTTP " + status, status == 403);
        });
        return lines;
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }

    private static void check(List<String> lines, String name, Check check) {
        try {
            lines.add(name + ": " + check.run());
        } catch (Exception | AssertionError e) {
            lines.add(name + ": " + McpProtocol.describe(e) + " -> FAIL");
        }
    }

    private static String verdict(String what, boolean ok) {
        return what + " -> " + (ok ? "OK" : "FAIL");
    }

    /** Calls a tool and returns its structured answer, failing on a tool error. */
    private JsonObject tool(String name, JsonObject arguments, boolean modern) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        JsonObject r = result(send("tools/call", params, modern, name, true));
        if (r.get("isError").getAsBoolean()) {
            throw new IllegalStateException("tool error: " + r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        }
        return r.getAsJsonObject("structuredContent");
    }

    private static JsonObject result(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!body.has("result")) {
            throw new IllegalStateException("no result: " + response.body());
        }
        return body.getAsJsonObject("result");
    }

    private HttpResponse<String> send(String method, JsonObject params, boolean modern, String name, boolean withToken) throws Exception {
        return http.send(post(method, params, modern, name, withToken).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A POST of one request; a modern one carries its version in {@code _meta} and in the MCP headers. */
    private HttpRequest.Builder post(String method, JsonObject params, boolean modern, String name, boolean withToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (withToken) {
            b.header("Authorization", "Bearer " + token);
        }
        JsonObject p = params.deepCopy();
        if (modern) {
            JsonObject meta = new JsonObject();
            meta.addProperty(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.LATEST);
            meta.add(McpProtocol.META_CLIENT_CAPABILITIES, new JsonObject());
            p.add("_meta", meta);
            b.header("MCP-Protocol-Version", McpProtocol.LATEST).header("Mcp-Method", method);
            if (name != null) {
                b.header("Mcp-Name", name);
            }
        } else {
            b.header("MCP-Protocol-Version", "2025-11-25");
        }
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", ++nextId);
        body.addProperty("method", method);
        body.add("params", p);
        return b.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }
}
