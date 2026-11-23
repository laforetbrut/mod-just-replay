package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vyrriox.justreplay.format.ReplayStorage;

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
    /** Every tool the server must list, in this order: the read tools, the observation tools, then the control tools. */
    public static final List<String> TOOLS;
    /** Tools that wait on the game for long: a replay opening or closing, files written, audio decoded. */
    private static final java.util.Set<String> SLOW = java.util.Set.of("open_replay", "close_replay", "stop_recording",
            "save_instant_replay", "rename_replay", "delete_replay", "edit_audio_clip");

    static {
        List<String> all = new ArrayList<>(READ_TOOLS);
        all.addAll(McpObserveTools.TOOLS);
        all.addAll(McpControlTools.TOOLS);
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
     * @param actors at least one actor of the editor must be there too
     * @param width  width of the video frame asked for; the window frame is asked at {@code width * 4 / 5}
     * @param height height of the video frame
     */
    public record Look(int tick, List<String> types, boolean actors, int width, int height) {
    }

    /** A picture a check received, for the game to decode with its own decoder. */
    public record Picture(String label, byte[] data, int width, int height) {
    }

    /** Lines of the observation checks and the pictures they received. */
    public record Observation(List<String> lines, List<Picture> pictures) {
    }

    /**
     * What the control checks drive, through MCP only: a saved replay they open from a menu and close at the end, and
     * an export they start and cancel.
     *
     * @param replayId   id of a saved replay where the recording player is, holding no edit of the checks yet
     * @param markers    how many markers its project holds before the checks
     * @param tick       a tick of the replay the checks go to, with room for an export of 40 ticks after it
     * @param exportName name of the export they start, then cancel
     */
    public record Steer(String replayId, int markers, int tick, String exportName) {
    }

    /**
     * Lines of the control checks and what they added, for the game to find in project.json.
     *
     * @param cameraId     camera that got the keyframe, empty when the checks could not add it
     * @param keyframeTick tick of that keyframe
     * @param exportFile   file name of the export they cancelled, empty when none started
     */
    public record Steering(List<String> lines, String cameraId, int keyframeTick, String actorId, String markerId, String exportFile) {
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

    /**
     * Runs the control checks on a new daemon thread: open the replay, read its markers, go to a tick, place the view,
     * add a camera keyframe, an actor and a marker, refusals, save, read the three back, undo and redo, start an
     * export, follow it, cancel it, close the replay. The game must be on a menu with nothing open, and keep ticking.
     */
    public static CompletableFuture<Steering> steer(int port, String token, Steer steer) {
        CompletableFuture<Steering> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(new McpProbe(port, token).drive(steer));
            } catch (Throwable t) {
                result.complete(new Steering(List.of("mcp control probe stopped: " + McpProtocol.describe(t) + " -> FAIL"), "", -1, "",
                        "", ""));
            }
        }, "Just Replay MCP control probe");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    Steering drive(Steer steer) {
        List<String> lines = new ArrayList<>();
        String[] camera = {""};
        int[] keyTick = {-1};
        String[] actor = {""};
        String[] marker = {""};
        String[] export = {""};
        double[] eye = new double[3];
        double[] feet = new double[3];
        check(lines, "mcp open_replay from the menu", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("replay", steer.replayId());
            JsonObject r = tool("open_replay", args, true);
            boolean open = r.has("open") && r.get("open").getAsBoolean();
            String id = r.has("replay") ? r.getAsJsonObject("replay").get("id").getAsString() : "";
            return verdict("replay " + id + " open=" + open, open && id.equals(steer.replayId()));
        });
        check(lines, "mcp list_markers of the opened replay", () -> {
            JsonObject list = tool("list_markers", new JsonObject(), true);
            return verdict(list.get("count").getAsInt() + " markers (expected " + steer.markers() + ")",
                    list.get("count").getAsInt() == steer.markers());
        });
        check(lines, "mcp seek to tick " + steer.tick(), () -> {
            JsonObject args = new JsonObject();
            args.addProperty("tick", steer.tick());
            JsonObject r = tool("seek", args, false);
            return verdict("play head at " + r.get("tick").getAsInt() + " (" + r.get("time").getAsString() + ")",
                    r.get("tick").getAsInt() == steer.tick());
        });
        check(lines, "mcp get_entity finds the recording player", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", "recorder");
            JsonObject p = tool("get_entity", args, true).getAsJsonObject("entity").getAsJsonObject("position");
            feet[0] = p.get("x").getAsDouble();
            feet[1] = p.get("y").getAsDouble();
            feet[2] = p.get("z").getAsDouble();
            eye[0] = feet[0];
            eye[1] = feet[1] + 3.0;
            eye[2] = feet[2] - 5.0;
            return verdict("at " + point(p), true);
        });
        check(lines, "mcp set_camera places the free camera", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("x", eye[0]);
            args.addProperty("y", eye[1]);
            args.addProperty("z", eye[2]);
            args.add("lookAt", array(feet[0], feet[1] + 1.0, feet[2]));
            args.addProperty("fov", 60);
            JsonObject c = tool("set_camera", args, false).getAsJsonObject("camera");
            boolean aimed = Math.abs(c.get("yaw").getAsDouble()) < 1.0 && c.get("pitch").getAsDouble() > 0.0;
            return verdict("eye " + point(c) + " yaw " + c.get("yaw").getAsDouble() + " pitch " + c.get("pitch").getAsDouble()
                    + " fov " + c.get("fov").getAsDouble(), aimed && Math.abs(c.get("fov").getAsDouble() - 60.0) < 1.0E-6);
        });
        check(lines, "mcp edit_camera_keyframe adds a keyframe at the play head", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("action", "add");
            args.addProperty("x", eye[0]);
            args.addProperty("y", eye[1]);
            args.addProperty("z", eye[2]);
            args.add("lookAt", array(feet[0], feet[1] + 1.0, feet[2]));
            args.addProperty("fov", 60);
            JsonObject r = tool("edit_camera_keyframe", args, true);
            JsonObject k = r.getAsJsonObject("keyframe");
            camera[0] = r.getAsJsonObject("camera").get("id").getAsString();
            keyTick[0] = k.get("tick").getAsInt();
            double y = k.get("y").getAsDouble();
            return verdict("keyframe at " + keyTick[0] + " on camera " + r.getAsJsonObject("camera").get("name").getAsString()
                    + ", feet y " + y, keyTick[0] == steer.tick() && Math.abs(y - (eye[1] - McpCamera.EYE_HEIGHT)) < 1.0E-3);
        });
        check(lines, "mcp edit_actor adds an actor", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("action", "add");
            args.addProperty("type", "minecraft:villager");
            args.addProperty("name", "MCP actor");
            args.addProperty("x", feet[0] + 2.0);
            args.addProperty("y", feet[1]);
            args.addProperty("z", feet[2] + 2.0);
            args.addProperty("scale", 1.5);
            JsonObject a = tool("edit_actor", args, false).getAsJsonObject("actor");
            actor[0] = a.get("id").getAsString();
            return verdict("actor " + actor[0] + " " + a.get("type").getAsString() + " scale " + a.get("scale").getAsDouble(),
                    "minecraft:villager".equals(a.get("type").getAsString()) && Math.abs(a.get("scale").getAsDouble() - 1.5) < 1.0E-6);
        });
        check(lines, "mcp edit_marker adds a marker", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("action", "add");
            args.addProperty("name", "MCP marker");
            args.addProperty("note", "Placed over MCP by the self test");
            args.addProperty("tick", steer.tick() + 20);
            args.addProperty("color", "green");
            JsonObject m = tool("edit_marker", args, true).getAsJsonObject("marker");
            marker[0] = m.get("id").getAsString();
            return verdict("marker " + marker[0] + " at " + m.get("tick").getAsInt(), m.get("tick").getAsInt() == steer.tick() + 20);
        });
        check(lines, "mcp refuses a tick outside the replay", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("action", "add");
            args.addProperty("tick", 99_999_999);
            String error = toolError("edit_marker", args);
            return verdict("'" + error + "'", error.contains("outside the replay"));
        });
        check(lines, "mcp refuses to delete a replay by default", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("replay", steer.replayId());
            String error = toolError("delete_replay", args);
            JsonObject query = new JsonObject();
            query.addProperty("query", steer.replayId());
            int left = tool("list_replays", query, false).get("total").getAsInt();
            return verdict("'" + error + "', replay still listed: " + (left == 1), error.contains("Allow deleting files") && left == 1);
        });
        check(lines, "mcp save_project", () -> {
            JsonObject r = tool("save_project", new JsonObject(), true);
            return verdict(r.get("file").getAsString() + ", " + r.getAsJsonObject("holds").get("actors").getAsInt() + " actors",
                    r.get("saved").getAsBoolean() && r.get("file").getAsString().endsWith(ReplayStorage.PROJECT));
        });
        check(lines, "mcp get_project reads back the keyframe, the actor and the marker", () -> {
            JsonObject args = new JsonObject();
            JsonArray sections = new JsonArray();
            sections.add("cameras");
            sections.add("actors");
            args.add("sections", sections);
            args.addProperty("detail", "full");
            JsonObject project = tool("get_project", args, false);
            boolean key = false;
            for (JsonElement c : project.getAsJsonArray("cameras")) {
                if (c.getAsJsonObject().get("id").getAsString().equals(camera[0])) {
                    for (JsonElement k : c.getAsJsonObject().getAsJsonArray("keyframes")) {
                        key |= k.getAsJsonObject().get("tick").getAsInt() == keyTick[0];
                    }
                }
            }
            boolean found = false;
            for (JsonElement a : project.getAsJsonArray("actors")) {
                found |= a.getAsJsonObject().get("id").getAsString().equals(actor[0]);
            }
            boolean listed = markerListed(marker[0]);
            return verdict("keyframe=" + key + " actor=" + found + " marker=" + listed, key && found && listed);
        });
        check(lines, "mcp undo then redo the marker", () -> {
            tool("undo", new JsonObject(), true);
            boolean gone = !markerListed(marker[0]);
            JsonObject r = tool("redo", new JsonObject(), false);
            boolean back = markerListed(marker[0]);
            return verdict("gone after undo=" + gone + ", back after redo=" + back, gone && back && r.get("redone").getAsInt() == 1);
        });
        check(lines, "mcp start_export", () -> {
            JsonObject args = new JsonObject();
            args.addProperty("from", steer.tick());
            args.addProperty("to", steer.tick() + 40);
            args.addProperty("name", steer.exportName());
            args.addProperty("fps", 20);
            args.addProperty("rangeInName", false);
            JsonObject r = tool("start_export", args, true);
            JsonObject e = r.getAsJsonObject("export");
            export[0] = e.get("file").getAsString();
            return verdict("started " + export[0] + ", " + e.get("totalFrames").getAsLong() + " frames",
                    r.get("started").getAsBoolean() && e.get("active").getAsBoolean() && export[0].startsWith(steer.exportName()));
        });
        check(lines, "mcp get_export follows the export", () -> {
            long until = System.currentTimeMillis() + 30_000L;
            JsonObject e = tool("get_export", new JsonObject(), false);
            while (e.get("active").getAsBoolean() && e.get("frames").getAsLong() == 0 && System.currentTimeMillis() < until) {
                Thread.sleep(200L);
                e = tool("get_export", new JsonObject(), false);
            }
            return verdict(e.get("state").getAsString() + ", " + e.get("frames").getAsLong() + " of " + e.get("totalFrames").getAsLong()
                    + " frames (" + e.get("percent").getAsInt() + " %)", e.get("active").getAsBoolean() && e.get("frames").getAsLong() > 0);
        });
        check(lines, "mcp cancel_export", () -> {
            JsonObject r = tool("cancel_export", new JsonObject(), true);
            return verdict("cancelled " + r.get("file").getAsString(), r.get("cancelled").getAsBoolean());
        });
        check(lines, "mcp get_export after the cancel", () -> {
            long until = System.currentTimeMillis() + 60_000L;
            JsonObject e = tool("get_export", new JsonObject(), true);
            while (!e.get("done").getAsBoolean() && System.currentTimeMillis() < until) {
                Thread.sleep(200L);
                e = tool("get_export", new JsonObject(), true);
            }
            return verdict(e.get("state").getAsString(), "cancelled".equals(e.get("state").getAsString()) && !e.get("written").getAsBoolean());
        });
        check(lines, "mcp close_replay", () -> {
            JsonObject r = tool("close_replay", new JsonObject(), false);
            return verdict("closed " + r.get("replayId").getAsString(), r.get("closed").getAsBoolean()
                    && r.get("replayId").getAsString().equals(steer.replayId()));
        });
        return new Steering(lines, camera[0], keyTick[0], actor[0], marker[0], export[0]);
    }

    private boolean markerListed(String id) throws Exception {
        for (JsonElement m : tool("list_markers", new JsonObject(), true).getAsJsonArray("markers")) {
            if (m.getAsJsonObject().get("id").getAsString().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray array(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) {
            a.add(v);
        }
        return a;
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
            int actors = 0;
            for (JsonElement e : list.getAsJsonArray("entities")) {
                if ("recorder".equals(e.getAsJsonObject().get("ref").getAsString())) {
                    recorder[0] = e.getAsJsonObject();
                }
                if (McpEntity.ACTOR.equals(e.getAsJsonObject().get("source").getAsString())) {
                    actors++;
                }
            }
            found.add("recording player " + (recorder[0] == null ? "missing" : "at " + point(recorder[0].getAsJsonObject("position"))));
            found.add(actors + " actors");
            return verdict(list.get("total").getAsInt() + " entities: " + String.join(", ", found), all && recorder[0] != null
                    && (!look.actors() || actors > 0) && list.get("tick").getAsInt() == look.tick());
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
        JsonObject r = call(name, arguments, modern);
        if (r.get("isError").getAsBoolean()) {
            throw new IllegalStateException("tool error: " + r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        }
        return r;
    }

    /** Calls a tool that must refuse the call, and returns its error message. */
    private String toolError(String name, JsonObject arguments) throws Exception {
        JsonObject r = call(name, arguments, true);
        if (!r.get("isError").getAsBoolean()) {
            throw new IllegalStateException(name + " accepted the call: " + r.get("structuredContent"));
        }
        return r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private JsonObject call(String name, JsonObject arguments, boolean modern) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        Duration wait = McpObserveTools.TOOLS.contains(name) ? FRAME_WAIT
                : SLOW.contains(name) ? Duration.ofSeconds(McpPlayTools.OPEN_TIMEOUT_SECONDS + 30L) : Duration.ofSeconds(20);
        HttpResponse<String> response = http.send(post("tools/call", params, modern, name, true).timeout(wait).build(),
                HttpResponse.BodyHandlers.ofString());
        return result(response);
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
