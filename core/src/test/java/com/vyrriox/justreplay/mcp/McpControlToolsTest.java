package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control tools through the protocol, on a fake game: their schemas and annotations, the settings that refuse
 * them (deleting files is off by default, a read only server refuses every change), the strict reading of their
 * arguments, refused changes that leave the project untouched, and each family of tools doing what the editor does.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpControlToolsTest {
    @TempDir
    Path dir;
    private FakeObserver world;
    private FakeControl control;
    private McpProtocol protocol;
    private boolean readOnly;
    private boolean allowDeleting;
    private int nextId;

    @BeforeEach
    void setUp() throws Exception {
        Path replays = Files.createDirectories(dir.resolve("replays"));
        Path exports = Files.createDirectories(dir.resolve("exports"));
        world = new FakeObserver();
        world.addCamera();
        FakeControl.writeReplay(replays, "fake-replay", "Fake", 1200, world.project);
        control = new FakeControl(world, replays, exports);
        McpGame game = new McpGame() {
            @Override
            public McpState state() {
                McpState s = new McpState();
                s.recording.active = control.recording;
                return s;
            }

            @Override
            public OpenReplay openReplay() {
                McpObserver.Replay r = world.replay();
                return r == null ? null : new OpenReplay(r.id(), r.meta(), r.project(), r.tick(), r.durationTicks());
            }

            @Override
            public Path replaysRoot() {
                return replays;
            }
        };
        McpRegistry registry = McpReadTools.registry(game);
        McpObserveTools.register(registry, world);
        McpControlTools.register(registry, game, world, control);
        GameThreadQueue queue = new GameThreadQueue(4);
        // This test thread stands for the game thread: calls to the game run at once.
        queue.runPending(0);
        protocol = new McpProtocol(registry, queue, new McpProtocol.Info("justreplay", "Just Replay", "1.2.0", "test"), () -> 5000L,
                McpProtocol.Guards.of(() -> readOnly, () -> allowDeleting));
    }

    // ------------------------------------------------------------------ schemas and guards

    @Test
    void everyControlToolIsListedAnnotatedAndStrict() {
        JsonArray tools = rpc("tools/list", new JsonObject()).getAsJsonObject("result").getAsJsonArray("tools");
        List<String> control = new ArrayList<>();
        Set<String> reading = Set.of("get_export", "list_exports", "list_files");
        Set<String> deleting = Set.of("delete_replay", "delete_export");
        Set<String> adding = Set.of("open_replay", "close_replay", "set_playback", "seek", "set_camera", "save_project",
                "start_recording", "stop_recording", "save_instant_replay", "start_export");
        for (JsonElement t : tools) {
            JsonObject tool = t.getAsJsonObject();
            String name = tool.get("name").getAsString();
            if (!McpControlTools.TOOLS.contains(name)) {
                continue;
            }
            control.add(name);
            assertTrue(tool.get("description").getAsString().length() >= 80, name + " has a short description");
            JsonObject a = tool.getAsJsonObject("annotations");
            assertEquals(reading.contains(name), a.get("readOnlyHint").getAsBoolean(), name);
            if (!reading.contains(name)) {
                assertEquals(!adding.contains(name), a.get("destructiveHint").getAsBoolean(), name);
            }
            assertFalse(a.get("openWorldHint").getAsBoolean(), name);
            JsonObject schema = tool.getAsJsonObject("inputSchema");
            assertFalse(schema.get("additionalProperties").getAsBoolean(), name);
            for (Map.Entry<String, JsonElement> p : schema.getAsJsonObject("properties").entrySet()) {
                JsonObject property = p.getValue().getAsJsonObject();
                assertTrue(property.has("type"), name + "." + p.getKey());
                assertTrue(property.get("description").getAsString().length() >= 10, name + "." + p.getKey());
                if (property.has("default") && property.has("enum")) {
                    assertTrue(property.getAsJsonArray("enum").contains(property.get("default")), name + "." + p.getKey());
                }
            }
            if (deleting.contains(name)) {
                assertTrue(a.get("destructiveHint").getAsBoolean(), name);
            }
        }
        assertEquals(McpControlTools.TOOLS, control);
        assertEquals(35, control.size());
    }

    @Test
    void deletingFilesIsRefusedUntilThePlayerAllowsIt() throws Exception {
        Path other = FakeControl.writeReplay(control.replays, "old-one", "Old one", 600, null);
        Path video = Files.writeString(control.exports.resolve("clip.mp4"), "video");
        String refused = toolError("delete_replay", "replay", "old-one");
        assertTrue(refused.contains("Allow deleting files") && refused.contains("Nothing was deleted"), refused);
        assertTrue(Files.isDirectory(other));
        refused = toolError("delete_export", "file", "clip.mp4");
        assertTrue(refused.contains("mcp.allowDestructive"), refused);
        assertTrue(Files.exists(video));

        allowDeleting = true;
        assertTrue(toolError("delete_replay", "replay", "fake-replay").contains("is open"));
        assertEquals("old-one", ok("delete_replay", "replay", "old-one").get("deleted").getAsString());
        assertFalse(Files.exists(other));
        assertEquals("clip.mp4", ok("delete_export", "file", "clip.mp4").get("deleted").getAsString());
        assertFalse(Files.exists(video));
        assertTrue(toolError("delete_export", "file", "../replays").contains("No file"));
    }

    @Test
    void aReadOnlyServerRefusesEveryChangeAndKeepsReading() {
        readOnly = true;
        int markers = world.project.markers.size();
        String refused = toolError("edit_marker", "action", "add", "name", "Nope");
        assertTrue(refused.contains("read only") && refused.contains("nothing changed"), refused);
        assertTrue(toolError("seek", "tick", 10).contains("read only"));
        assertTrue(toolError("start_export").contains("read only"));
        assertEquals(markers, world.project.markers.size());
        assertEquals(40, world.cursor);
        assertEquals(0, control.changes);
        ok("get_export");
        ok("list_files");
        ok("get_state");
    }

    // ------------------------------------------------------------------ arguments

    @Test
    void wrongArgumentsGetMessagesThatSayWhatToFix() {
        assertTrue(toolError("edit_marker", "name", "x").contains("'action' is required"));
        assertTrue(toolError("edit_marker", "action", "paint").contains("must be one of: add, update, remove"));
        assertTrue(toolError("edit_marker", "action", "add", "colour", "red").contains("Unknown argument 'colour'"));
        assertTrue(toolError("edit_marker", "action", "add", "tick", 5000).contains("outside the replay (0 to 1200 ticks"));
        assertTrue(toolError("edit_marker", "action", "add", "tick", "ten").contains("must be a whole number"));
        assertTrue(toolError("edit_marker", "action", "remove", "marker", world.moment.id, "name", "x")
                .contains("'name' does not apply to action=remove"));
        assertTrue(toolError("edit_marker", "action", "add", "end", 2000).contains("past the end of the replay"));
        assertTrue(toolError("edit_marker", "action", "add", "tick", 50, "end", 40).contains("must be after the first tick"));
        assertTrue(toolError("edit_marker", "action", "update", "marker", "nope", "name", "x").contains("No marker has the id"));
        assertTrue(toolError("edit_camera_keyframe", "action", "add", "x", 1.0, "y", 2.0).contains("x, y and z together"));
        assertTrue(toolError("edit_actor", "action", "add", "type", "minecraft:dragon_of_doom").contains("not an entity type"));
        assertTrue(toolError("edit_actor", "action", "add", "mainHand", "minecraft:lightsaber").contains("not an item"));
        assertTrue(toolError("edit_actor", "action", "add", "skin", "nobody.png").contains("No file 'nobody.png' in the skins folder"));
        assertTrue(toolError("edit_actor", "action", "add", "type", "minecraft:zombie", "skin", "hero.png").contains("for player actors"));
        assertTrue(toolError("edit_actor", "action", "add", "hiddenParts", strings("tail")).contains("takes a list of: head"));
        assertTrue(toolError("set_playback", "speed", 3.0).contains("must be one of 0.1, 0.25, 0.5, 1, 2, 4, 8"));
        assertTrue(toolError("seek", "tick", 5, "by", 3).contains("exactly one of"));
        assertTrue(toolError("seek", "time", "1:75").contains("must be a time"));
        assertTrue(toolError("set_camera", "view", "path", "x", 1.0).contains("only apply to view=free"));
        assertTrue(toolError("edit_overlay", "action", "add", "color", "red").contains("#RRGGBB"));
        assertTrue(toolError("edit_effect", "action", "add", "type", "VIGNETTE", "values", obj("amount", 7.0))
                .contains("'amount' must be from 0 to 1"));
        assertTrue(toolError("edit_effect", "action", "add", "type", "VIGNETTE", "values", obj("glow", 1.0))
                .contains("VIGNETTE has no parameter 'glow'"));
        assertTrue(toolError("start_export", "from", 10).contains("from and to together"));
        assertTrue(toolError("start_export", "width", 640).contains("width and height together"));
        assertTrue(toolError("undo").contains("Nothing to undo"));
        assertEquals(0, control.changes);
    }

    @Test
    void aRefusedChangeLeavesTheProjectAsItWas() {
        String overlay = ok("edit_overlay", "action", "add", "text", "Hello").getAsJsonObject("overlay").get("id").getAsString();
        String before = ReplayStorage.GSON.toJson(world.project);
        int changes = control.changes;
        // The text is fine, the end is not: nothing of the call may land.
        assertTrue(toolError("edit_overlay", "action", "update", "overlay", overlay, "text", "Changed", "start", 300, "end", 200)
                .contains("must be after 'start'"));
        assertTrue(toolError("edit_camera", "action", "update", "camera", "Path", "name", "Renamed", "lookAt", "nobody")
                .contains("No entity 'nobody'"));
        assertTrue(toolError("edit_camera", "action", "update", "camera", "Path", "target", "nobody").contains("for FOLLOW cameras"));
        assertEquals(before, ReplayStorage.GSON.toJson(world.project));
        assertEquals(changes, control.changes);
    }

    // ------------------------------------------------------------------ playback

    @Test
    void openSeekPlayAndPlaceTheView() {
        FakeControl.writeReplay(control.replays, "other", "Other", 900, null);
        JsonObject opened = ok("open_replay", "replay", "other");
        assertTrue(opened.get("open").getAsBoolean());
        assertEquals("other", opened.getAsJsonObject("replay").get("id").getAsString());
        assertTrue(ok("open_replay", "replay", "other").get("alreadyOpen").getAsBoolean());
        assertTrue(toolError("open_replay", "replay", "missing").contains("No saved replay has the id 'missing'"));

        world.project.addMarker(Marker.instant(300, "Boss fight"));
        assertEquals(300, ok("seek", "marker", "boss fight").get("tick").getAsInt());
        assertTrue(toolError("seek", "time", "1:02.5").contains("outside the replay (0 to 900 ticks"));
        assertEquals(400, ok("seek", "time", "20").get("tick").getAsInt());
        assertEquals(380, ok("seek", "by", -20).get("tick").getAsInt());
        assertEquals(0, ok("seek", "by", -5000).get("tick").getAsInt());

        JsonObject play = ok("set_playback", "playing", true, "speed", 2.0);
        assertTrue(play.get("playing").getAsBoolean());
        assertEquals(2.0, play.get("speed").getAsDouble(), 1.0E-6);
        assertEquals(500, ok("set_playback", "until", 500).get("pausesAt").getAsInt());
        assertTrue(control.calls.contains("playTo 500"));

        JsonObject c = ok("set_camera", "x", 0.0, "y", 70.0, "z", -10.0, "lookAt", arr(0.0, 70.0, 0.0), "fov", 50.0)
                .getAsJsonObject("camera");
        assertEquals(0.0, c.get("yaw").getAsDouble(), 1.0E-6);
        assertEquals(0.0, c.get("pitch").getAsDouble(), 1.0E-6);
        assertEquals(50.0, world.camera.fov(), 1.0E-6);
        assertEquals(-90.0, ok("set_camera", "lookAt", arr(10.0, 70.0, -10.0)).getAsJsonObject("camera").get("yaw").getAsDouble(), 1.0E-6);
        ok("set_camera", "view", "path");
        assertTrue(control.cameraPath);
        assertEquals("Pig", ok("set_camera", "view", "entity", "entity", FakeObserver.PIG_UUID).get("entity").getAsString());

        assertTrue(ok("close_replay").get("closed").getAsBoolean());
        assertTrue(toolError("seek", "tick", 1).contains("No replay is open"));
    }

    // ------------------------------------------------------------------ edits

    @Test
    void cameraKeyframesFollowTheEditorsRules() {
        JsonObject add = ok("edit_camera_keyframe", "action", "add", "camera", "Path", "tick", 100, "x", 5.0, "y", 71.62, "z", 3.0,
                "yaw", 45.0);
        JsonObject k = add.getAsJsonObject("keyframe");
        assertEquals(70.0, k.get("y").getAsDouble(), 1.0E-9);
        assertEquals(71.62, k.getAsJsonArray("eye").get(1).getAsDouble(), 1.0E-9);
        assertEquals(70.0, k.get("fov").getAsDouble(), 1.0E-6);
        assertFalse(add.get("replaced").getAsBoolean());
        assertTrue(add.get("canUndo").getAsBoolean());
        // A keyframe at a tick that has one replaces it, as the keyframe button does.
        assertTrue(ok("edit_camera_keyframe", "action", "add", "camera", "Path", "tick", 100).get("replaced").getAsBoolean());
        ReplayProject.Camera path = world.project.camera("cam");
        assertEquals(3, path.keyframes.size());
        ok("edit_camera_keyframe", "action", "update", "camera", "Path", "tick", 100, "newTick", 200, "roll", 15.0);
        assertNotNull(path.keyframeAt(200));
        assertEquals(15.0F, path.keyframeAt(200).roll, 1.0E-6F);
        assertNull(path.keyframeAt(100));
        assertTrue(toolError("edit_camera_keyframe", "action", "update", "camera", "cam", "tick", 200, "newTick", 600)
                .contains("already has a keyframe"));
        ok("edit_camera_keyframe", "action", "remove", "camera", "cam", "tick", 200);
        assertEquals(2, path.keyframes.size());

        String fixed = ok("edit_camera", "action", "add", "type", "STATIC", "name", "Wide", "x", 1.0, "y", 81.62, "z", 2.0)
                .getAsJsonObject("camera").get("id").getAsString();
        assertEquals(80.0, world.project.camera(fixed).y, 1.0E-9);
        assertEquals(fixed, world.project.selectedCamera);
        assertTrue(toolError("edit_camera_keyframe", "action", "add", "camera", "Wide").contains("only PATH cameras have keyframes"));
        assertTrue(toolError("edit_camera", "action", "update", "camera", "Wide", "curve", "LINEAR").contains("for PATH cameras"));
        ok("edit_cut", "action", "add", "tick", 0, "camera", "Path");
        JsonObject cut = ok("edit_cut", "action", "add", "tick", 300, "camera", "Wide", "transition", "DISSOLVE", "transitionTicks", 20)
                .getAsJsonObject("cut");
        assertEquals("DISSOLVE", cut.get("transition").getAsString());
        assertTrue(toolError("edit_cut", "action", "add", "tick", 400, "camera", "Wide").contains("already live"));
        JsonObject removed = ok("edit_camera", "action", "remove", "camera", "Wide");
        assertEquals(1, removed.get("cutsRemoved").getAsInt());
        ok("edit_camera", "action", "remove", "camera", "Camera 1");
        assertTrue(toolError("edit_camera", "action", "remove", "camera", "Path").contains("at least one camera"));
        String follow = ok("edit_camera", "action", "add", "type", "FOLLOW", "target", FakeObserver.PIG_UUID, "followMode", "ORBIT")
                .getAsJsonObject("camera").get("id").getAsString();
        assertEquals(FakeObserver.PIG_UUID, world.project.camera(follow).target);
        assertEquals("Pig", world.project.camera(follow).targetName);
        assertTrue(toolError("edit_camera", "action", "add", "type", "FOLLOW").contains("needs 'target'"));
    }

    @Test
    void actorsKeyframesAndActions() {
        JsonObject actor = ok("edit_actor", "action", "add", "type", "minecraft:villager", "name", "Bob", "x", 4.0, "y", 64.0, "z", 4.0,
                "scale", 2.0, "mainHand", "minecraft:stick").getAsJsonObject("actor");
        String id = actor.get("id").getAsString();
        ReplayProject.ActorData bob = world.project.actors.get(0);
        assertEquals(id, bob.id);
        assertEquals(2.0F, bob.scale, 1.0E-6F);
        assertEquals(40, bob.keyframes.get(0).tick);
        JsonObject player = ok("edit_actor", "action", "add", "skin", "hero.png", "slim", true, "hiddenParts", strings("head", "left_arm"),
                "hiddenArmor", strings("feet")).getAsJsonObject("actor");
        ReplayProject.ActorData steve = world.project.actors.get(1);
        assertEquals("Steve", steve.name);
        assertEquals(1 | 4, steve.hiddenParts);
        assertEquals(8, steve.hiddenArmor);
        assertEquals(1.0, steve.keyframes.get(0).x, 1.0E-9);
        assertEquals("head", player.getAsJsonArray("hiddenPartNames").get(0).getAsString());
        JsonObject model = ok("edit_actor", "action", "add", "type", "model", "model", "robot.bbmodel").getAsJsonObject("actor");
        assertEquals("robot", model.get("name").getAsString());
        assertEquals("idle", model.get("modelAnimation").getAsString());
        assertTrue(toolError("edit_actor", "action", "update", "actor", "robot", "modelAnimation", "dance").contains("no animation 'dance'"));

        ok("edit_actor", "action", "update", "actor", "Bob", "visible", false);
        assertTrue(control.rebuilt.contains(id));
        ok("edit_actor_keyframe", "action", "add", "actor", id, "tick", 100, "x", 8.0, "y", 64.0, "z", 8.0, "yaw", 270.0);
        assertEquals(-90.0F, bob.keyframeAt(100).yaw, 1.0E-6F);
        JsonObject walk = ok("edit_actor_action", "action", "add", "actor", "Bob", "tick", 60, "type", "WALK_TO", "target", arr(1.0, 64.0, 2.5))
                .getAsJsonObject("actorAction");
        assertEquals("1.00 64.00 2.50", walk.get("param").getAsString());
        assertEquals("0.00 64.00 0.00", ok("edit_actor_action", "action", "add", "actor", "Bob", "tick", 80, "type", "RUN_TO")
                .getAsJsonObject("actorAction").get("param").getAsString());
        ok("edit_actor_action", "action", "add", "actor", "Bob", "tick", 60, "type", "WAVE");
        assertTrue(toolError("edit_actor_action", "action", "remove", "actor", "Bob", "tick", 60).contains("give 'type'"));
        ok("edit_actor_action", "action", "update", "actor", "Bob", "tick", 60, "type", "WAVE", "duration", 40);
        ok("edit_actor_action", "action", "remove", "actor", "Bob", "tick", 60, "type", "WALK_TO");
        assertEquals(2, bob.actions.size());
        ok("edit_actor", "action", "add_skin_change", "actor", steve.id, "tick", 200, "skin", "hero.png");
        assertEquals(1, steve.skinChanges.size());
        assertTrue(toolError("edit_actor", "action", "add_skin_change", "actor", "Bob").contains("Only player actors"));
        ok("edit_actor", "action", "remove", "actor", "Bob");
        assertTrue(control.removed.contains(id));
        assertEquals(2, world.project.actors.size());
    }

    @Test
    void recordedEntitiesAndTheirTypes() {
        JsonObject e = ok("edit_entity", "action", "update", "entity", FakeObserver.PIG_UUID, "hidden", true, "hideFrom", 100, "glow", true);
        assertTrue(e.get("kept").getAsBoolean());
        ReplayProject.EntityEdit pig = world.project.entityEdit(FakeObserver.PIG_UUID);
        assertEquals("Pig", pig.label);
        assertTrue(pig.hidden && pig.glow);
        ok("edit_entity", "action", "set_offset", "entity", FakeObserver.PIG_UUID, "tick", 50, "y", 2.0);
        assertEquals(2.0, pig.offsets.get(0).y, 1.0E-9);
        assertTrue(toolError("edit_entity", "action", "update", "entity", FakeObserver.PIG_UUID, "hiddenParts", strings("head"))
                .contains("for recorded players"));
        assertTrue(toolError("edit_entity", "action", "update", "entity", "actor-1", "glow", true).contains("edit_actor changes it"));
        assertEquals("player Alex", ok("edit_entity", "action", "update", "entity", "recorder", "hiddenParts", strings("head"))
                .getAsJsonObject("entityEdit").get("label").getAsString());
        ok("edit_entity", "action", "reset", "entity", FakeObserver.PIG_UUID);
        assertNull(world.project.entityEdit(FakeObserver.PIG_UUID));
        ok("edit_entity", "action", "hide_type", "type", "minecraft:bat");
        assertEquals(List.of("minecraft:bat"), world.project.hiddenEntityTypes);
        ok("edit_entity", "action", "show_type", "type", "minecraft:bat");
        assertTrue(world.project.hiddenEntityTypes.isEmpty());
    }

    @Test
    void audioEffectsOverlaysAndTheWorld() {
        JsonObject clip = ok("edit_audio_clip", "action", "add", "file", "theme.ogg", "volume", 0.8, "fadeIn", 20).getAsJsonObject("clip");
        assertEquals(400.0, clip.get("out").getAsDouble(), 1.0E-9);
        assertEquals("Track 1", clip.get("trackName").getAsString());
        assertTrue(toolError("edit_audio_clip", "action", "update", "clip", clip.get("id").getAsString(), "out", 500.0)
                .contains("past the end of the file"));
        assertTrue(toolError("edit_audio_clip", "action", "add", "file", "broken.ogg").contains("cannot be played"));
        ok("edit_audio_track", "action", "add", "name", "Music", "volume", 0.5);
        ok("edit_audio_clip", "action", "move", "clip", clip.get("id").getAsString(), "track", "Music");
        assertEquals(1, world.project.audioTracks.get(1).clips.size());
        ok("set_audio_mix", "gameVolume", 0.5, "soundGains", obj("footsteps", 0.25), "ducking", true);
        assertEquals(0.25F, world.project.soundGains.get("footsteps"), 1.0E-6F);
        assertTrue(toolError("set_audio_mix", "soundGains", obj("lasers", 1.0)).contains("not a kind of game sound"));

        String fx = ok("edit_effect", "action", "add", "type", "VIGNETTE", "values", obj("amount", 0.6)).getAsJsonObject("effect")
                .get("id").getAsString();
        ok("edit_effect", "action", "set_key", "effect", fx, "param", "amount", "tick", 100, "value", 0.2);
        assertEquals(1, world.project.effects.get(0).keys.get("amount").size());
        ok("edit_effect", "action", "add", "type", "LETTERBOX", "values", obj("ratio", 3.0), "index", 0);
        assertEquals("LETTERBOX", world.project.effects.get(0).type);
        assertTrue(toolError("edit_effect", "action", "set_key", "effect", world.project.effects.get(0).id, "param", "ratio", "value", 1.0)
                .contains("is a choice"));
        ok("set_effects", "fadeIn", 20, "fadeWhite", true);
        assertEquals(20, world.project.fadeIn);

        JsonObject text = ok("edit_overlay", "action", "add", "text", "Hello", "color", "#FF8800", "x", 1.0, "y", 70.0, "z", 2.0)
                .getAsJsonObject("overlay");
        assertEquals("#FF8800", text.get("color").getAsString());
        assertEquals(40, text.get("start").getAsInt());
        assertEquals(140, text.get("end").getAsInt());
        assertTrue(toolError("edit_overlay", "action", "add", "type", "IMAGE", "text", "x").contains("for TEXT overlays"));
        assertEquals("logo.png", ok("edit_overlay", "action", "add", "type", "IMAGE").getAsJsonObject("overlay").get("image").getAsString());
        ok("edit_overlay", "action", "set_key", "overlay", text.get("id").getAsString(), "param", "opacity", "value", 0.0, "tick", 60);
        assertTrue(toolError("edit_overlay", "action", "update", "overlay", text.get("id").getAsString(), "facing", "FLAT")
                .contains("anchor=BLOCK"));

        ok("set_world", "timeMode", "FIXED", "time", 18000.0, "weather", "THUNDER");
        assertEquals("THUNDER", world.project.world.weather);
        ok("set_world", "action", "set_key", "key", "rain", "tick", 100, "value", 0.5);
        assertEquals(1, world.project.world.rainKeys.size());
        assertTrue(toolError("set_world", "action", "set_key", "key", "rain", "value", 2.0).contains("from 0 to 1"));
        ok("edit_speed", "action", "set_key", "tick", 100, "speed", 0.5, "ramp", true);
        ok("edit_speed", "action", "add_timelapse", "from", 200, "to", 400, "seconds", 2.0);
        assertTrue(toolError("edit_speed", "action", "add_timelapse", "from", 300, "to", 500).contains("overlaps"));
        assertEquals(1, world.project.speed.timelapses.size());
    }

    @Test
    void markersUndoRedoAndSave() throws Exception {
        JsonObject spot = ok("edit_marker", "action", "add", "name", "Spot", "x", 1.0, "y", 64.0, "z", 2.0, "note", "Wide here")
                .getAsJsonObject("marker");
        assertEquals("POINT", spot.get("type").getAsString());
        assertEquals("minecraft:overworld", world.project.marker(spot.get("id").getAsString()).dimension);
        JsonObject range = ok("edit_marker", "action", "add", "tick", 100, "end", 200, "color", "blue").getAsJsonObject("marker");
        assertEquals("RANGE", range.get("type").getAsString());
        JsonObject hero = ok("edit_marker", "action", "add", "entity", "recorder").getAsJsonObject("marker");
        assertEquals(Marker.RECORDER, world.project.marker(hero.get("id").getAsString()).target);
        ok("edit_marker", "action", "update", "marker", range.get("id").getAsString(), "end", -1);
        assertEquals(Marker.INSTANT, world.project.marker(range.get("id").getAsString()).type);
        int count = world.project.markers.size();
        ok("edit_marker", "action", "remove", "marker", "Spot");
        assertEquals(count - 1, world.project.markers.size());

        JsonObject undone = ok("undo", "steps", 2);
        assertEquals(2, undone.get("undone").getAsInt());
        assertTrue(undone.get("canRedo").getAsBoolean());
        assertNotNull(world.project.marker(spot.get("id").getAsString()));
        assertEquals(Marker.RANGE, world.project.marker(range.get("id").getAsString()).type);
        ok("redo");
        assertEquals(Marker.INSTANT, world.project.marker(range.get("id").getAsString()).type);

        JsonObject saved = ok("save_project");
        assertEquals("fake-replay/project.json", saved.get("file").getAsString());
        ReplayProject disk = ReplayStorage.readProject(control.replays.resolve("fake-replay"));
        assertNotNull(disk.marker(hero.get("id").getAsString()));
    }

    @Test
    void recordingRenamingAndExporting() throws Exception {
        assertTrue(toolError("start_recording").contains("A replay is open"));
        ok("edit_camera", "action", "select", "camera", "Path");
        ok("close_replay");
        ok("start_recording", "minutes", 2);
        assertEquals(2400, control.recordingTicks);
        JsonObject stopped = ok("stop_recording", "name", "Boss fight");
        assertEquals("Boss fight", stopped.get("name").getAsString());
        assertTrue(stopped.get("renamed").getAsBoolean());
        assertTrue(control.calls.contains("stop offerName=false"));
        assertTrue(toolError("stop_recording").contains("No recording is running"));
        JsonObject buffer = ok("save_instant_replay");
        assertTrue(buffer.get("saved").getAsBoolean());
        assertTrue(control.calls.contains("buffer offerName=true"));
        control.busy.add(buffer.get("replayId").getAsString());
        assertTrue(toolError("rename_replay", "replay", buffer.get("replayId").getAsString(), "name", "x").contains("is open, being"));
        JsonObject renamed = ok("rename_replay", "replay", stopped.get("replayId").getAsString(), "name", "Boss fight 2");
        assertEquals("Boss fight 2", renamed.get("name").getAsString());
        assertTrue(toolError("rename_replay", "replay", "nope", "name", "x").contains("No saved replay"));

        ok("open_replay", "replay", "fake-replay");
        JsonObject started = ok("start_export", "name", "Trailer", "fps", 30);
        JsonObject export = started.getAsJsonObject("export");
        assertEquals(0, export.get("from").getAsInt());
        assertEquals(600, export.get("to").getAsInt());
        assertEquals(30, control.lastRequest.fps());
        assertNull(control.lastRequest.rangeInName());
        assertTrue(toolError("edit_marker", "action", "add").contains("export or a frame is being drawn"));
        assertTrue(ok("get_export").get("frames").getAsLong() > 0);
        assertTrue(ok("cancel_export").get("cancelled").getAsBoolean());
        JsonObject after = ok("get_export");
        assertEquals("cancelled", after.get("state").getAsString());
        assertFalse(after.get("written").getAsBoolean());
        assertTrue(toolError("cancel_export").contains("No video export is running"));
        world.project.camera("cam").keyframes.clear();
        assertTrue(toolError("start_export").contains("Nothing to export"));
        Files.writeString(control.exports.resolve("Trailer.mp4"), "x");
        assertEquals(1, ok("list_exports").get("total").getAsInt());
    }

    @Test
    void parseTimeReadsWhatPeopleWrite() {
        assertEquals(1250, McpControlTools.parseTime("1:02.5"));
        assertEquals(1250, McpControlTools.parseTime("62.5"));
        assertEquals(72_040, McpControlTools.parseTime("1:00:02"));
        assertEquals(-1, McpControlTools.parseTime("1:75"));
        assertEquals(-1, McpControlTools.parseTime("-3"));
        assertEquals(-1, McpControlTools.parseTime("ten"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonArray strings(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    private static JsonArray arr(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) {
            a.add(v);
        }
        return a;
    }

    private static JsonObject obj(String key, double value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
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

    private JsonObject ok(String tool, Object... args) {
        JsonObject result = call(tool, args).getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean(), tool + ": " + result);
        return result.getAsJsonObject("structuredContent");
    }

    private String toolError(String tool, Object... args) {
        JsonObject result = call(tool, args).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean(), tool + " accepted: " + result);
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }
}
