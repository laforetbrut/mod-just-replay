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
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * A small MCP client for the in-game self test: asks a running server over local HTTP what a real client would (the
 * legacy handshake, the modern discovery, the tool list, the read tools, a resource, two refusals; then the
 * observation tools, frames included) and turns each answer into a log line ending in {@code -> OK} or
 * {@code -> FAIL}. It runs on its own thread: the tools need the
 * game thread to answer, so the game must keep ticking meanwhile.
 *
 * @author vyrriox
 */
public final class McpProbe {
    /** Read tools the server must list first, in this order. */
    public static final List<String> READ_TOOLS = List.of("get_state", "list_replays", "get_project", "list_markers", "get_marker");
    /** Every tool the server must list, in this order: the read tools, then the observation tools. */
    public static final List<String> TOOLS;

    static {
        List<String> all = new ArrayList<>(READ_TOOLS);
        all.addAll(McpObserveTools.TOOLS);
        TOOLS = List.copyOf(all);
    }

    /** Waiting time of one request: a frame waits for the game to draw it, which is slow in a software renderer. */
    private static final Duration FRAME_WAIT = Duration.ofSeconds(McpObserveTools.FRAME_TIMEOUT_SECONDS + 30L);

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

    /**
     * What the observation checks look at in the replay open in the game.
     *
     * @param tick   a tick of the replay, not the play head, where the entities of {@code types} and the recording
     *               player are in the world and walking
     * @param types  entity types that must be there at {@code tick}
     * @param width  width of the video frame asked for; the window frame is asked at {@code width * 4 / 5}
     * @param height height of the video frame
     */
    public record Look(int tick, List<String> types, int width, int height) {
    }

    /** A picture a check received, for the game to decode with its own decoder. */
    public record Picture(String label, byte[] data, int width, int height) {
    }

    /** Lines of the observation checks and the pictures they received. */
    public record Observation(List<String> lines, List<Picture> pictures) {
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

    /**
     * Runs the observation checks on a new daemon thread: entities at a tick, one entity, a path, the world, a block,
     * the players, two frames and a projection. The game must keep ticking and drawing meanwhile.
     */
    public static CompletableFuture<Observation> observe(int port, String token, Look look) {
        CompletableFuture<Observation> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            List<Picture> pictures = new ArrayList<>();
            try {
                result.complete(new Observation(new McpProbe(port, token).look(look, pictures), pictures));
            } catch (Throwable t) {
                result.complete(new Observation(List.of("mcp observation probe stopped: " + McpProtocol.describe(t) + " -> FAIL"), pictures));
            }
        }, "Just Replay MCP observation probe");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    List<String> look(Look look, List<Picture> pictures) {
        List<String> lines = new ArrayList<>();
        JsonObject[] recorder = new JsonObject[1];
        JsonObject[] firstOfType = new JsonObject[1];
        check(lines, "mcp list_entities at tick " + look.tick(), () -> {
            JsonObject args = new JsonObject();
            args.addProperty("tick", look.tick());
            args.addProperty("limit", McpEntityQuery.MAX_LIMIT);
            JsonObject list = tool("list_entities", args, true);
            List<String> found = new ArrayList<>();
            boolean all = true;
            for (String type : look.types()) {
                int count = 0;
                for (JsonElement e : list.getAsJsonArray("entities")) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.get("type").getAsString().equals(type)) {
                        count++;
                        if (firstOfType[0] == null) {
                            firstOfType[0] = o;
                        }
                    }
                }
                found.add(type + " x" + count);
                all &= count > 0;
            }
            for (JsonElement e : list.getAsJsonArray("entities")) {
                if ("recorder".equals(e.getAsJsonObject().get("ref").getAsString())) {
                    recorder[0] = e.getAsJsonObject();
                }
            }
            found.add("recording player " + (recorder[0] == null ? "missing" : "at " + point(recorder[0].getAsJsonObject("position"))));
            return verdict(list.get("total").getAsInt() + " entities: " + String.join(", ", found), all && recorder[0] != null
                    && list.get("tick").getAsInt() == look.tick());
        });
        check(lines, "mcp list_entities within 64 blocks of the recording player", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("tick", look.tick());
            args.addProperty("near", "recorder");
            args.addProperty("radius", 64);
            JsonObject list = tool("list_entities", args, false);
            double last = -1.0;
            boolean ordered = true;
            for (JsonElement e : list.getAsJsonArray("entities")) {
                double d = e.getAsJsonObject().get("distance").getAsDouble();
                ordered &= d >= last && d <= 64.0;
                last = d;
            }
            return verdict(list.get("total").getAsInt() + " entities, nearest first=" + ordered, list.get("total").getAsInt() > 0 && ordered);
        });
        check(lines, "mcp get_entity", () -> {
            if (firstOfType[0] == null) {
                return verdict("no entity of the expected types to ask for", false);
            }
            JsonObject args = new JsonObject();
            args.addProperty("id", firstOfType[0].get("ref").getAsString());
            args.addProperty("tick", look.tick());
            JsonObject e = tool("get_entity", args, true).getAsJsonObject("entity");
            boolean same = e.get("type").getAsString().equals(firstOfType[0].get("type").getAsString())
                    && point(e.getAsJsonObject("position")).equals(point(firstOfType[0].getAsJsonObject("position")));
            return verdict(e.get("type").getAsString() + " '" + e.get("name").getAsString() + "' at " + point(e.getAsJsonObject("position"))
                    + ", same as the list=" + same, same && e.has("size"));
        });
        check(lines, "mcp get_entity_track of the recording player", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", "recorder");
            args.addProperty("from", Math.max(0, look.tick() - 40));
            args.addProperty("to", look.tick());
            args.addProperty("samples", 9);
            JsonObject track = tool("get_entity_track", args, false);
            int samples = track.getAsJsonArray("samples").size();
            double distance = track.getAsJsonObject("summary").get("distance").getAsDouble();
            return verdict(samples + " samples, " + distance + " blocks walked", samples >= 2 && distance > 0.0);
        });
        check(lines, "mcp get_world", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("tick", look.tick());
            JsonObject w = tool("get_world", args, true);
            JsonObject world = w.getAsJsonObject("world");
            return verdict(world.get("dimension").getAsString() + " at " + world.get("clock").getAsString() + ", " + world.get("weather").getAsString()
                    + ", replay of " + w.getAsJsonObject("replay").get("duration").getAsString(), !world.get("dimension").getAsString().isEmpty()
                    && w.getAsJsonObject("replay").get("durationTicks").getAsInt() > look.tick());
        });
        check(lines, "mcp get_block under the recording player", () -> {
            if (recorder[0] == null) {
                return verdict("no recording player", false);
            }
            JsonObject p = recorder[0].getAsJsonObject("position");
            JsonObject args = new JsonObject();
            args.addProperty("x", (int) Math.floor(p.get("x").getAsDouble()));
            args.addProperty("y", (int) Math.floor(p.get("y").getAsDouble() - 0.5));
            args.addProperty("z", (int) Math.floor(p.get("z").getAsDouble()));
            args.addProperty("tick", look.tick());
            JsonObject block = tool("get_block", args, false).getAsJsonObject("block");
            return verdict(block.get("loaded").getAsBoolean() ? block.get("id").getAsString() + " " + block.getAsJsonObject("properties") : "not loaded",
                    block.get("loaded").getAsBoolean());
        });
        check(lines, "mcp list_players", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("tick", look.tick());
            JsonObject players = tool("list_players", args, true);
            JsonObject r = players.getAsJsonObject("recorder");
            return verdict("recorded by '" + r.get("name").getAsString() + "' present=" + r.get("present").getAsBoolean() + ", "
                    + players.get("tabListTotal").getAsInt() + " in the tab list", r.get("present").getAsBoolean());
        });
        check(lines, "mcp render_frame " + look.width() + "x" + look.height() + " jpeg at tick " + look.tick(), () -> {
            JsonObject args = new JsonObject();
            args.addProperty("tick", look.tick());
            args.addProperty("width", look.width());
            args.addProperty("height", look.height());
            return picture(pictures, "video frame", tool("render_frame", args, true, true), McpImage.JPEG, look.width(), look.height());
        });
        int uiWidth = look.width() * 4 / 5;
        int uiHeight = look.height() * 4 / 5;
        check(lines, "mcp render_frame ui=true " + uiWidth + "x" + uiHeight + " png", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("ui", true);
            args.addProperty("format", McpImage.PNG);
            args.addProperty("width", uiWidth);
            args.addProperty("height", uiHeight);
            return picture(pictures, "window frame", tool("render_frame", args, false, true), McpImage.PNG, uiWidth, uiHeight);
        });
        check(lines, "mcp render_frame and project_to_screen from a free camera aimed at the recording player", () -> {
            if (recorder[0] == null) {
                return verdict("no recording player", false);
            }
            JsonObject p = recorder[0].getAsJsonObject("position");
            // Six blocks south of the player and two up, looking north and a little down at its chest.
            double x = p.get("x").getAsDouble();
            double y = p.get("y").getAsDouble() + 3.0;
            double z = p.get("z").getAsDouble() + 6.0;
            double pitch = Math.toDegrees(Math.atan2(2.0, 6.0));
            JsonObject camera = new JsonObject();
            camera.addProperty("tick", look.tick());
            camera.addProperty("camera", "free");
            camera.addProperty("x", x);
            camera.addProperty("y", y);
            camera.addProperty("z", z);
            camera.addProperty("yaw", 180.0);
            camera.addProperty("pitch", pitch);
            camera.addProperty("width", look.width());
            camera.addProperty("height", look.height());
            String drawn = picture(pictures, "free camera", tool("render_frame", camera.deepCopy(), true, true), McpImage.JPEG,
                    look.width(), look.height());
            JsonObject projection = camera.deepCopy();
            JsonArray entities = new JsonArray();
            entities.add("recorder");
            projection.add("entities", entities);
            JsonArray points = new JsonArray();
            JsonArray ahead = new JsonArray();
            // Ten blocks along the view from the eye: the centre of the picture.
            double[] forward = new McpCamera(x, y, z, 180.0, pitch, 0.0, 70.0, "free").forward();
            ahead.add(x + 10.0 * forward[0]);
            ahead.add(y + 10.0 * forward[1]);
            ahead.add(z + 10.0 * forward[2]);
            points.add(ahead);
            projection.add("points", points);
            JsonArray targets = tool("project_to_screen", projection, false).getAsJsonArray("targets");
            JsonObject centre = targets.get(0).getAsJsonObject();
            JsonObject player = targets.get(1).getAsJsonObject();
            double cx = centre.getAsJsonArray("screen").get(0).getAsDouble();
            double cy = centre.getAsJsonArray("screen").get(1).getAsDouble();
            boolean centred = Math.abs(cx - look.width() / 2.0) < 0.6 && Math.abs(cy - look.height() / 2.0) < 0.6;
            boolean seen = player.has("onScreen") && player.get("onScreen").getAsBoolean();
            return drawn.replace(" -> OK", "").replace(" -> FAIL", "") + "; look point at " + cx + "," + cy + ", player on the picture at "
                    + (player.has("screen") ? player.getAsJsonArray("screen").toString() : "nowhere")
                    + verdict("", drawn.endsWith("-> OK") && centred && seen);
        });
        return lines;
    }

    /** Checks the picture of a render_frame answer and keeps it for the game to decode. */
    private static String picture(List<Picture> pictures, String label, JsonObject result, String format, int width, int height) {
        JsonObject image = result.getAsJsonArray("content").get(0).getAsJsonObject();
        if (!"image".equals(image.get("type").getAsString())) {
            return verdict("first content is " + image.get("type").getAsString() + ", not an image", false);
        }
        byte[] data = Base64.getDecoder().decode(image.get("data").getAsString());
        int[] size = McpImage.headerSize(data);
        String mime = image.get("mimeType").getAsString();
        pictures.add(new Picture(label, data, width, height));
        JsonObject s = result.getAsJsonObject("structuredContent");
        JsonObject camera = s.getAsJsonObject("camera");
        return verdict(String.format(Locale.ROOT, "%s, %d bytes, header %s, camera %s at %.1f %.1f %.1f yaw %.1f", mime, data.length,
                        size == null ? "unreadable" : size[0] + "x" + size[1], camera.get("source").getAsString(), camera.get("x").getAsDouble(),
                        camera.get("y").getAsDouble(), camera.get("z").getAsDouble(), camera.get("yaw").getAsDouble()),
                mime.equals(McpImage.mimeType(format)) && size != null && size[0] == width && size[1] == height);
    }

    private static String point(JsonObject p) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
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
        return tool(name, arguments, modern, false).getAsJsonObject("structuredContent");
    }

    /** Calls a tool and returns its whole result ({@code content} included), failing on a tool error. */
    private JsonObject tool(String name, JsonObject arguments, boolean modern, boolean whole) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        HttpResponse<String> response = http.send(post("tools/call", params, modern, name, true)
                .timeout(McpObserveTools.TOOLS.contains(name) ? FRAME_WAIT : Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject r = result(response);
        if (r.get("isError").getAsBoolean()) {
            throw new IllegalStateException("tool error: " + r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        }
        return r;
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
