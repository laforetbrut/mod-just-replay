package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observation tools through the protocol, on a fake replay world: their schemas, the jumps to another tick and
 * back, filters and pages, the bounds of every answer, and the picture of render_frame read back by the JDK.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpObserveToolsTest {
    private FakeObserver world;
    private McpProtocol protocol;
    private GameThreadQueue queue;
    private int nextId;

    @BeforeEach
    void setUp() {
        world = new FakeObserver();
        McpRegistry registry = new McpRegistry();
        McpObserveTools.register(registry, world);
        queue = new GameThreadQueue(4);
        // This test thread stands for the game thread: calls to the game run at once.
        queue.runPending(0);
        protocol = new McpProtocol(registry, queue, new McpProtocol.Info("justreplay", "Just Replay", "1.2.0", "test"), () -> 2000L);
    }

    // ------------------------------------------------------------------ schemas

    @Test
    void everyToolHasAClearSchema() {
        JsonArray tools = rpc("tools/list", new JsonObject()).getAsJsonObject("result").getAsJsonArray("tools");
        List<String> names = new ArrayList<>();
        for (JsonElement t : tools) {
            JsonObject tool = t.getAsJsonObject();
            String name = tool.get("name").getAsString();
            names.add(name);
            assertTrue(tool.get("description").getAsString().length() >= 80, name + " has a short description");
            assertTrue(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean(), name);
            JsonObject schema = tool.getAsJsonObject("inputSchema");
            assertEquals("object", schema.get("type").getAsString(), name);
            assertFalse(schema.get("additionalProperties").getAsBoolean(), name);
            JsonObject properties = schema.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> p : properties.entrySet()) {
                JsonObject property = p.getValue().getAsJsonObject();
                String where = name + "." + p.getKey();
                assertTrue(property.has("type"), where);
                assertTrue(property.get("description").getAsString().length() >= 10, where);
                if (property.has("minimum")) {
                    assertTrue(property.get("minimum").getAsDouble() <= property.get("maximum").getAsDouble(), where);
                }
                if (property.has("default") && property.has("enum")) {
                    assertTrue(property.getAsJsonArray("enum").contains(property.get("default")), where);
                }
                if ("array".equals(property.get("type").getAsString())) {
                    assertTrue(property.has("maxItems") || property.has("uniqueItems"), where + " has no bound");
                }
            }
            if (schema.has("required")) {
                for (JsonElement r : schema.getAsJsonArray("required")) {
                    assertTrue(properties.has(r.getAsString()), name + " requires " + r);
                }
            }
        }
        assertEquals(McpObserveTools.TOOLS, names);
        JsonObject list = tools.get(1).getAsJsonObject().getAsJsonObject("inputSchema").getAsJsonObject("properties");
        assertEquals(McpEntityQuery.MAX_LIMIT, list.getAsJsonObject("limit").get("maximum").getAsInt());
    }

    @Test
    void slowToolsGetMoreTimeThanTheSettings() {
        McpRegistry registry = new McpRegistry();
        registry.add(McpTool.builder("wait").description("Says how long it may take.").offGameThread().timeoutSeconds(30)
                .handler((args, context) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("left", context.remainingMillis());
                    return McpToolResult.of(o);
                }).build());
        registry.add(McpTool.builder("quick").description("Says how long it may take.").offGameThread()
                .handler((args, context) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("left", context.remainingMillis());
                    return McpToolResult.of(o);
                }).build());
        protocol = new McpProtocol(registry, queue, new McpProtocol.Info("justreplay", "Just Replay", "1.2.0", "test"), () -> 2000L);
        assertTrue(ok("wait").get("left").getAsLong() > 25_000L);
        assertTrue(ok("quick").get("left").getAsLong() <= 2000L);
    }

    // ------------------------------------------------------------------ entities

    @Test
    void entitiesAtThePlayHeadNeedNoJump() {
        JsonObject list = ok("list_entities");
        assertEquals(40, list.get("tick").getAsInt());
        assertEquals(6, list.get("total").getAsInt());
        assertTrue(world.seeks.isEmpty(), world.seeks.toString());
        JsonObject first = list.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals("actor", first.get("source").getAsString());
        assertEquals("actor:actor-1", first.get("ref").getAsString());
        assertEquals("actor-1", first.get("actorId").getAsString());
        assertFalse(list.has("nextOffset"));
    }

    @Test
    void anotherTickIsReachedAndLeft() {
        JsonObject list = ok("list_entities", "tick", 100, "types", strings("pig"));
        assertEquals(List.of(99, 100, 40), world.seeks);
        assertEquals(40, world.cursor);
        JsonObject pig = list.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals(20.0, pig.getAsJsonObject("position").get("x").getAsDouble(), 1.0E-9);
        // At the play head, the motion the game keeps (0.5 a tick) is all there is.
        JsonObject here = ok("list_entities", "types", strings("pig")).getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals(10.0, here.getAsJsonObject("velocity").get("speed").getAsDouble(), 1.0E-9);
        // One block every 10 ticks: 2 blocks per second, from the two ticks read.
        assertEquals(2.0, pig.getAsJsonObject("velocity").get("speed").getAsDouble(), 1.0E-9);
        assertEquals(100, list.get("tick").getAsInt());
        assertEquals(40, list.getAsJsonObject("replay").get("playheadTick").getAsInt());
    }

    @Test
    void spheresAroundPointsMarkersAndEntities() {
        JsonObject around = ok("list_entities", "marker", world.spot.id, "radius", 3);
        assertEquals(1, around.get("total").getAsInt());
        assertEquals("marker " + world.spot.id, around.getAsJsonObject("center").get("from").getAsString());
        JsonObject pig = around.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals(2.0, pig.get("distance").getAsDouble(), 1.0E-9);
        // An entity marker centres on its entity, here the recording player.
        JsonObject hero = ok("list_entities", "marker", world.hero.id, "radius", 0.5);
        assertEquals("recorder", hero.getAsJsonArray("entities").get(0).getAsJsonObject().get("ref").getAsString());
        JsonObject near = ok("list_entities", "near", "1", "radius", 1);
        assertEquals(1, near.get("total").getAsInt());
        JsonObject point = ok("list_entities", "x", 40, "y", 64, "z", 40, "radius", 2);
        assertEquals(2, point.get("total").getAsInt());
        assertTrue(toolError("list_entities", "marker", world.moment.id).contains("no place"));
        assertTrue(toolError("list_entities", "x", 1, "marker", world.spot.id).contains("one centre"));
        assertTrue(toolError("list_entities", "radius", 5).contains("centre"));
        assertTrue(toolError("list_entities", "x", 1).contains("x, y and z"));
    }

    @Test
    void pagesAndLimitsAreBounded() {
        world.extraZombies = 300;
        world.longName = "N".repeat(500);
        JsonObject first = ok("list_entities", "types", strings("zombie"), "limit", 200);
        assertEquals(301, first.get("total").getAsInt());
        assertEquals(200, first.get("count").getAsInt());
        assertEquals(200, first.get("nextOffset").getAsInt());
        JsonObject second = ok("list_entities", "types", strings("zombie"), "limit", 200, "offset", 200);
        assertEquals(101, second.get("count").getAsInt());
        assertFalse(second.has("nextOffset"));
        assertTrue(toolError("list_entities", "limit", 500).contains("1 to 200"));
        // The largest pages of the largest entities stay a size a model can read.
        JsonObject full = ok("list_entities", "types", strings("zombie"), "limit", 200, "detail", "full");
        assertEquals(McpEntityQuery.MAX_FULL_LIMIT, full.get("count").getAsInt());
        assertTrue(text("list_entities", "types", strings("zombie"), "limit", 200, "detail", "full").length() < 150_000);
        assertTrue(text("list_entities", "types", strings("zombie"), "limit", 200).length() < 250_000);
        JsonArray types = new JsonArray();
        for (int i = 0; i < 40; i++) {
            types.add("t" + i);
        }
        assertTrue(toolError("list_entities", "types", types).contains("at most 32"));
    }

    @Test
    void oneEntityInFull() {
        JsonObject e = ok("get_entity", "id", "recorder").getAsJsonObject("entity");
        assertEquals("Alex", e.get("name").getAsString());
        assertEquals("Excalibur", e.getAsJsonArray("equipment").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(world.hero.id, e.getAsJsonArray("markers").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(e.has("size"));
        JsonObject rider = ok("get_entity", "id", "11").getAsJsonObject("entity");
        assertEquals("minecraft:horse", rider.getAsJsonObject("vehicle").get("type").getAsString());
        JsonObject horse = ok("get_entity", "id", "10").getAsJsonObject("entity");
        assertEquals(11, horse.getAsJsonArray("passengers").get(0).getAsJsonObject().get("id").getAsInt());
        assertTrue(toolError("get_entity", "id", "nobody").contains("No entity 'nobody'"));
        assertTrue(toolError("get_entity").contains("required"));
        assertTrue(toolError("get_entity", "id", "1", "tick", 5000).contains("past the end"));
    }

    @Test
    void aTrackSamplesTheStretchAndComesBack() {
        JsonObject track = ok("get_entity_track", "id", FakeObserver.PIG_UUID, "from", 0, "to", 200, "samples", 5);
        assertEquals(50, track.get("step").getAsInt());
        JsonArray samples = track.getAsJsonArray("samples");
        assertEquals(5, samples.size());
        assertEquals(10.0, samples.get(0).getAsJsonArray().get(1).getAsDouble(), 1.0E-9);
        assertEquals(30.0, samples.get(4).getAsJsonArray().get(1).getAsDouble(), 1.0E-9);
        JsonObject summary = track.getAsJsonObject("summary");
        assertEquals(20.0, summary.get("distance").getAsDouble(), 1.0E-9);
        assertEquals(2.0, summary.get("topSpeed").getAsDouble(), 1.0E-9);
        assertEquals(40, world.cursor);
        assertEquals(40, (int) world.seeks.get(world.seeks.size() - 1));
        // Defaults: five seconds before and after the play head.
        JsonObject around = ok("get_entity_track", "id", "1");
        assertEquals(0, around.get("from").getAsInt());
        assertEquals(100, around.get("to").getAsInt());
        assertTrue(around.getAsJsonArray("samples").size() <= McpObserveTools.DEFAULT_TRACK_SAMPLES);
        world.duration = 100_000;
        assertTrue(toolError("get_entity_track", "id", "1", "from", 0, "to", 20_000).contains("at most 12000"));
        assertTrue(toolError("get_entity_track", "id", "1", "from", 0, "to", 10_000, "step", 1).contains("more than 500"));
        assertTrue(toolError("get_entity_track", "id", "1", "from", 50, "to", 10).contains("before"));
        assertTrue(toolError("get_entity_track", "id", "ghost", "from", 0, "to", 10).contains("No entity 'ghost'"));
        assertEquals(1, McpObserveTools.trackStep(0, 0, null, 100));
        assertEquals(3, McpObserveTools.trackStep(0, 297, null, 100));
    }

    @Test
    void noJumpDuringAnExport() {
        world.exporting = true;
        assertTrue(toolError("list_entities", "tick", 100).contains("export"));
        assertTrue(toolError("render_frame").contains("export"));
        assertEquals(6, ok("list_entities").get("total").getAsInt());
        assertTrue(world.seeks.isEmpty());
    }

    @Test
    void noReplayNoAnswer() {
        world.open = false;
        for (String tool : List.of("list_entities", "get_world", "list_players")) {
            assertTrue(toolError(tool).contains("No replay is open"), tool);
        }
        assertTrue(toolError("render_frame").contains("No replay is open"));
        // The window itself can still be seen.
        JsonObject frame = call("render_frame", "ui", true).getAsJsonObject("result");
        assertFalse(frame.get("isError").getAsBoolean(), frame.toString());
    }

    // ------------------------------------------------------------------ world, block, players

    @Test
    void worldClockAndSettings() {
        JsonObject w = ok("get_world", "tick", 200);
        assertEquals("minecraft:overworld", w.getAsJsonObject("world").get("dimension").getAsString());
        assertEquals(1200, w.getAsJsonObject("world").get("dayTime").getAsInt());
        assertEquals("07:12", w.getAsJsonObject("world").get("clock").getAsString());
        assertEquals("rain", w.getAsJsonObject("world").get("weather").getAsString());
        assertEquals("RECORDED", w.getAsJsonObject("projectSettings").get("timeMode").getAsString());
        assertEquals(1200, w.getAsJsonObject("replay").get("durationTicks").getAsInt());
        assertEquals(List.of(200, 40), world.seeks);
        assertEquals("06:00", McpObserveTools.clock(0));
        assertEquals("18:00", McpObserveTools.clock(12_000));
        assertEquals("00:00", McpObserveTools.clock(-6000));
    }

    @Test
    void blocksAndPlayers() {
        JsonObject log = ok("get_block", "x", 1, "y", 63, "z", 2).getAsJsonObject("block");
        assertEquals("minecraft:oak_log", log.get("id").getAsString());
        assertEquals("y", log.getAsJsonObject("properties").get("axis").getAsString());
        assertFalse(ok("get_block", "x", 5000, "y", 63, "z", 2).getAsJsonObject("block").get("loaded").getAsBoolean());
        assertTrue(toolError("get_block", "x", 1, "y", 63).contains("required"));
        JsonObject players = ok("list_players", "limit", 1);
        assertEquals("Alex", players.getAsJsonObject("recorder").get("name").getAsString());
        assertTrue(players.getAsJsonObject("recorder").get("present").getAsBoolean());
        assertEquals(2, players.get("tabListTotal").getAsInt());
        assertEquals(1, players.getAsJsonArray("tabList").size());
        assertEquals("survival", players.getAsJsonArray("present").get(0).getAsJsonObject().get("gameMode").getAsString());
    }

    // ------------------------------------------------------------------ frames

    @Test
    void aFrameIsAPictureOfTheSizeAskedFor() throws Exception {
        JsonObject result = call("render_frame", "width", 320, "height", 180).getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject image = result.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("image", image.get("type").getAsString());
        assertEquals("image/jpeg", image.get("mimeType").getAsString());
        byte[] data = Base64.getDecoder().decode(image.get("data").getAsString());
        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(data));
        assertEquals(320, picture.getWidth());
        assertEquals(180, picture.getHeight());
        int rgb = picture.getRGB(160, 90);
        assertTrue(Math.abs(((rgb >> 16) & 0xFF) - 200) <= 4, Integer.toHexString(rgb));
        assertEquals("text", result.getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString());
        JsonObject s = result.getAsJsonObject("structuredContent");
        assertEquals(data.length, s.getAsJsonObject("picture").get("bytes").getAsInt());
        assertEquals("current", s.getAsJsonObject("camera").get("source").getAsString());
        McpObserver.FrameRequest asked = world.frames.get(0);
        assertEquals(40, asked.tick());
        assertFalse(asked.ui());
        // Without camera keyframes the frame keeps the view the player has.
        assertNotNull(asked.camera());
    }

    @Test
    void pngFramesAreExactAndBigOnesAreScaledDown() throws Exception {
        JsonObject result = call("render_frame", "format", "png", "width", 5000, "tick", 100).getAsJsonObject("result");
        JsonObject s = result.getAsJsonObject("structuredContent");
        assertEquals(1280, s.getAsJsonObject("picture").get("width").getAsInt());
        assertEquals(720, s.getAsJsonObject("picture").get("height").getAsInt());
        assertTrue(s.getAsJsonObject("picture").has("note"));
        byte[] data = Base64.getDecoder().decode(result.getAsJsonArray("content").get(0).getAsJsonObject().get("data").getAsString());
        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(data));
        assertEquals(1280, picture.getWidth());
        assertEquals(0xC86432, picture.getRGB(10, 10) & 0xFFFFFF);
        assertEquals(100, world.frames.get(0).tick());
        assertTrue(toolError("render_frame", "tick", 2000).contains("past the end"));
    }

    @Test
    void camerasAreChosenAsAsked() {
        // A free camera is drawn from where it is placed.
        ok("render_frame", "x", 1, "y", 70, "z", 2, "yaw", 90, "fov", 50);
        McpCamera free = world.frames.get(0).camera();
        assertEquals(90.0, free.yaw());
        assertEquals(50.0, free.fov());
        assertEquals("free", free.source());
        assertTrue(toolError("render_frame", "camera", "free", "x", 1).contains("x, y and z"));
        assertTrue(toolError("render_frame", "camera", "current", "yaw", 4).contains("only applies"));
        assertTrue(toolError("render_frame", "camera", "project").contains("no camera keyframes"));
        // With a camera path the video frame follows it and the window frame is moved onto it.
        world.addCamera();
        JsonObject frame = ok("render_frame");
        assertNull(world.frames.get(1).camera());
        assertEquals("project", frame.getAsJsonObject("camera").get("source").getAsString());
        // The window keeps the player's view unless a camera is asked for.
        JsonObject window = ok("render_frame", "ui", true);
        assertTrue(world.frames.get(2).ui());
        assertNull(world.frames.get(2).camera());
        assertEquals("current", window.getAsJsonObject("camera").get("source").getAsString());
        ok("render_frame", "ui", true, "camera", "project");
        assertEquals("project", world.frames.get(3).camera().source());
        ok("render_frame", "ui", true, "camera", "current");
        assertNull(world.frames.get(4).camera());
    }

    @Test
    void aFrameThatNeverComesFailsCleanly() {
        world.holdFrames = true;
        McpContext shortWait = new McpContext(queue, System.nanoTime() + 300_000_000L, "test");
        McpArgs.Invalid e = assertThrows(McpArgs.Invalid.class,
                () -> McpObserveTools.renderFrame(world, new McpArgs(new JsonObject()), shortWait));
        assertTrue(e.getMessage().contains("not drawn in time"), e.getMessage());
        assertTrue(world.heldFrame.isCancelled(), "the drawing is stopped");
    }

    // ------------------------------------------------------------------ project_to_screen

    @Test
    void pointsMarkersAndEntitiesLandOnTheFrame() {
        // The current camera stands at (0, 70, 0) looking south: a point 10 blocks ahead is the centre.
        JsonObject out = ok("project_to_screen", "points", points(new double[]{0, 70, 10}, new double[]{0, 70, -10}),
                "markers", strings("all"), "entities", strings("1", "ghost"), "width", 640, "height", 360);
        JsonArray targets = out.getAsJsonArray("targets");
        JsonObject ahead = targets.get(0).getAsJsonObject();
        assertEquals(320.0, ahead.getAsJsonArray("screen").get(0).getAsDouble(), 1.0E-6);
        assertEquals(180.0, ahead.getAsJsonArray("screen").get(1).getAsDouble(), 1.0E-6);
        assertTrue(ahead.get("onScreen").getAsBoolean());
        assertFalse(targets.get(1).getAsJsonObject().get("inFront").getAsBoolean());
        // 'all' gives the markers with a place: the pen (POINT) and the hero (ENTITY), not the moment.
        assertEquals("marker", targets.get(2).getAsJsonObject().get("kind").getAsString());
        assertEquals(world.spot.id, targets.get(2).getAsJsonObject().get("id").getAsString());
        assertTrue(targets.get(3).getAsJsonObject().has("feet"));
        JsonObject pig = targets.get(4).getAsJsonObject();
        assertEquals("entity", pig.get("kind").getAsString());
        assertTrue(pig.getAsJsonArray("head").get(1).getAsDouble() < pig.getAsJsonArray("feet").get(1).getAsDouble());
        assertFalse(targets.get(5).getAsJsonObject().get("placed").getAsBoolean());
        assertEquals(6, targets.size());
        assertTrue(toolError("project_to_screen").contains("Give points"));
        assertTrue(toolError("project_to_screen", "markers", strings("nope")).contains("no marker"));
    }

    @Test
    void windowFramesAreCroppedLikeThePicture() {
        // A 2:1 window seen as a square picture: the centre stays the centre, the window's edges are cut off.
        world.window = new int[]{1000, 500};
        JsonObject out = ok("project_to_screen", "points", points(new double[]{0, 70, 10}), "width", 400, "height", 400, "ui", true);
        JsonArray screen = out.getAsJsonArray("targets").get(0).getAsJsonObject().getAsJsonArray("screen");
        assertEquals(200.0, screen.get(0).getAsDouble(), 1.0E-6);
        assertEquals(200.0, screen.get(1).getAsDouble(), 1.0E-6);
        McpCamera c = world.currentCamera();
        McpObserveTools.Screen window = new McpObserveTools.Screen(c, 400, 400, true, 1000, 500);
        McpCamera.Projection plain = c.project(-5, 70, 10, 1000, 500);
        McpCamera.Projection cropped = window.project(-5, 70, 10);
        assertEquals((plain.screenX() - 250.0) * 400.0 / 500.0, cropped.screenX(), 1.0E-6);
    }

    // ------------------------------------------------------------------ helpers

    private static JsonArray strings(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    private static JsonArray points(double[]... values) {
        JsonArray a = new JsonArray();
        for (double[] v : values) {
            JsonArray p = new JsonArray();
            for (double d : v) {
                p.add(d);
            }
            a.add(p);
        }
        return a;
    }

    private JsonObject call(String tool, Object... args) {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        JsonObject a = new JsonObject();
        for (int i = 0; i + 1 < args.length; i += 2) {
            Object v = args[i + 1];
            String key = (String) args[i];
            if (v instanceof Number n) {
                a.addProperty(key, n);
            } else if (v instanceof Boolean b) {
                a.add(key, new JsonPrimitive(b));
            } else if (v instanceof JsonElement element) {
                a.add(key, element);
            } else {
                a.addProperty(key, (String) v);
            }
        }
        params.add("arguments", a);
        return rpc("tools/call", params);
    }

    private JsonObject rpc(String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", ++nextId);
        o.addProperty("method", method);
        o.add("params", params);
        return protocol.handle(o.toString(), name -> null).body();
    }

    private String text(String tool, Object... args) {
        String text = call(tool, args).getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text")
                .getAsString();
        assertTrue(text.length() > 1000, text);
        return text;
    }

    private JsonObject ok(String tool, Object... args) {
        JsonObject result = call(tool, args).getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        return result.getAsJsonObject("structuredContent");
    }

    private String toolError(String tool, Object... args) {
        JsonObject result = call(tool, args).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean(), result.toString());
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }
}
