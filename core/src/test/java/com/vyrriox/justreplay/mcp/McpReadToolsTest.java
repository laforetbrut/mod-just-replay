package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.audio.AudioClip;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.editor.overlay.Overlay;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read tools on a fake game: a live project for the open replay and saved replays in a temporary folder.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpReadToolsTest {
    @TempDir
    Path root;

    private final McpState state = new McpState();
    private McpGame.OpenReplay open;
    private McpProtocol protocol;
    private int nextId;

    @BeforeEach
    void setUp() throws IOException {
        state.minecraft = "1.21.1";
        state.loader = "neoforge";
        state.modVersion = "1.2.0";
        state.screen = "editor";
        state.replay.open = true;
        state.replay.replayId = "live";
        state.replay.tick = 40;
        state.replay.durationTicks = 300;
        state.editor.open = true;
        state.editor.mode = "ADVANCED";

        ReplayMeta liveMeta = new ReplayMeta();
        liveMeta.name = "Live one";
        liveMeta.durationTicks = 300;
        open = new McpGame.OpenReplay("live", liveMeta, project(300), 40, 300);

        saved("2026-11-20_10-00-00", "Boss fight", "play.example.net", 1_000L, true, "{\"cameras\":[{\"id\":\"c1\",\"name\":\"Disk camera\"}],"
                + "\"markers\":[{\"id\":\"m-disk\",\"name\":\"From disk\",\"note\":\"Saved note\",\"tick\":10}]}");
        saved("2026-11-21_09-00-00", "Build", "", 3_000L, false, null);
        saved("2026-11-19_08-00-00", "Broken", "", 500L, false, "{broken");

        GameThreadQueue queue = new GameThreadQueue(4);
        // This test thread stands for the game thread: calls to the game run at once.
        queue.runPending(0);
        McpGame game = new McpGame() {
            @Override
            public McpState state() {
                return state;
            }

            @Override
            public OpenReplay openReplay() {
                return open;
            }

            @Override
            public Path replaysRoot() {
                return root;
            }
        };
        protocol = new McpProtocol(McpReadTools.registry(game), queue, new McpProtocol.Info("justreplay", "Just Replay", "1.2.0",
                McpReadTools.INSTRUCTIONS), () -> 2000L);
    }

    private static ReplayProject project(int duration) {
        ReplayProject p = new ReplayProject();
        ReplayProject.Camera camera = new ReplayProject.Camera();
        camera.id = "cam-1";
        camera.name = "Orbit";
        for (int tick : new int[]{20, 80, 140}) {
            ReplayProject.CameraKeyframe k = new ReplayProject.CameraKeyframe();
            k.tick = tick;
            k.x = tick;
            camera.keyframes.add(k);
        }
        p.cameras.add(camera);
        ReplayProject.ActorData actor = new ReplayProject.ActorData();
        actor.id = "actor-1";
        actor.name = "Steve";
        ReplayProject.ActorAction wave = new ReplayProject.ActorAction();
        wave.tick = 60;
        wave.type = "SWING_MAIN";
        actor.actions.add(wave);
        p.actors.add(actor);
        AudioTrack track = new AudioTrack();
        track.name = "Music";
        AudioClip clip = new AudioClip();
        clip.file = "theme.ogg";
        clip.start = 20;
        clip.length = 100;
        track.clips.add(clip);
        p.audioTracks.add(track);
        Overlay title = new Overlay();
        title.text = "Episode 1";
        p.overlays.add(title);
        Marker moment = Marker.instant(30, "Moment");
        moment.note = "Wide shot,\nthen cut";
        p.addMarker(moment);
        p.addMarker(Marker.range(100, 160, duration, "Stretch"));
        p.addMarker(Marker.entity(50, Marker.RECORDER, "Player", "Hero"));
        p.normalize();
        return p;
    }

    private void saved(String id, String name, String server, long startedAt, boolean singleplayer, String projectJson) throws IOException {
        Path dir = Files.createDirectories(root.resolve(id));
        Files.write(dir.resolve(ReplayStorage.STREAM), new byte[]{1, 2, 3});
        ReplayMeta meta = new ReplayMeta();
        meta.name = name;
        meta.server = server;
        meta.startedAt = startedAt;
        meta.singleplayer = singleplayer;
        meta.durationTicks = 1200;
        meta.mc = "1.21.1";
        meta.markers.add(new ReplayMeta.Marker(100, "Recorded"));
        Files.writeString(dir.resolve(ReplayStorage.META), ReplayStorage.GSON.toJson(meta), StandardCharsets.UTF_8);
        if (projectJson != null) {
            Files.writeString(dir.resolve(ReplayStorage.PROJECT), projectJson, StandardCharsets.UTF_8);
        }
    }

    private JsonObject call(String tool, Object... args) {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        JsonObject a = new JsonObject();
        for (int i = 0; i + 1 < args.length; i += 2) {
            Object v = args[i + 1];
            if (v instanceof Number n) {
                a.addProperty((String) args[i], n);
            } else if (v instanceof JsonArray array) {
                a.add((String) args[i], array);
            } else {
                a.addProperty((String) args[i], (String) v);
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
        McpProtocol.Reply reply = protocol.handle(o.toString(), name -> null);
        return reply.body();
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

    @Test
    void theStateSaysWhatIsOpen() {
        JsonObject s = ok("get_state");
        assertEquals(20, s.get("ticksPerSecond").getAsInt());
        assertEquals("neoforge", s.get("loader").getAsString());
        JsonObject replay = s.getAsJsonObject("replay");
        assertTrue(replay.get("open").getAsBoolean());
        assertEquals("0:02.0", replay.get("time").getAsString());
        assertEquals("0:15.0", replay.get("duration").getAsString());
        assertFalse(s.getAsJsonObject("recording").get("active").getAsBoolean());
        assertEquals("ADVANCED", s.getAsJsonObject("editor").get("mode").getAsString());
    }

    @Test
    void savedReplaysComeNewestFirstWithTheirMetadata() {
        JsonObject list = ok("list_replays");
        assertEquals(3, list.get("total").getAsInt());
        assertEquals("live", list.get("openReplay").getAsString());
        JsonArray replays = list.getAsJsonArray("replays");
        assertEquals("2026-11-21_09-00-00", replays.get(0).getAsJsonObject().get("id").getAsString());
        JsonObject boss = replays.get(1).getAsJsonObject();
        assertEquals("Boss fight", boss.get("name").getAsString());
        assertEquals("play.example.net", boss.get("server").getAsString());
        assertEquals("1:00.0", boss.get("duration").getAsString());
        assertEquals("1970-01-01T00:00:01Z", boss.get("recordedAt").getAsString());
        assertTrue(boss.get("hasProject").getAsBoolean());
        assertEquals(1, boss.get("recordingMarkers").getAsInt());
        assertEquals(3, boss.get("sizeBytes").getAsLong());
        assertFalse(boss.toString().contains(root.toString()), "no path of the computer is given");

        assertEquals(1, ok("list_replays", "query", "BOSS").get("total").getAsInt());
        JsonObject page = ok("list_replays", "limit", 1, "offset", 2);
        assertEquals(1, page.get("count").getAsInt());
        assertEquals("2026-11-19_08-00-00", page.getAsJsonArray("replays").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(toolError("list_replays", "limit", 0).contains("from 1 to 500"));
    }

    @Test
    void theOpenProjectIsReadLiveAndShortenedInASummary() {
        JsonObject p = ok("get_project");
        assertTrue(p.getAsJsonObject("replay").get("open").getAsBoolean());
        assertEquals("summary", p.get("detail").getAsString());
        JsonObject overview = p.getAsJsonObject("overview");
        assertEquals(1, overview.get("cameras").getAsInt());
        assertEquals(1, overview.get("actions").getAsInt());
        assertEquals(3, overview.get("markers").getAsInt());
        assertEquals(1, overview.get("audioClips").getAsInt());
        JsonObject camera = p.getAsJsonArray("cameras").get(0).getAsJsonObject();
        assertEquals("Orbit", camera.get("name").getAsString());
        JsonObject keyframes = camera.getAsJsonObject("keyframes");
        assertEquals(3, keyframes.get("count").getAsInt());
        assertEquals(20, keyframes.get("firstTick").getAsInt());
        assertEquals(140, keyframes.get("lastTick").getAsInt());
        JsonObject action = p.getAsJsonArray("actions").get(0).getAsJsonObject();
        assertEquals("Steve", action.get("actorName").getAsString());
        assertEquals("0:03.0", action.get("time").getAsString());
        assertEquals("theme.ogg", p.getAsJsonObject("audio").getAsJsonArray("tracks").get(0).getAsJsonObject()
                .getAsJsonArray("clips").get(0).getAsJsonObject().get("file").getAsString());
        assertEquals("Episode 1", p.getAsJsonArray("overlays").get(0).getAsJsonObject().get("text").getAsString());

        JsonArray sections = new JsonArray();
        sections.add("cameras");
        JsonObject full = ok("get_project", "sections", sections, "detail", "full");
        assertEquals(3, full.getAsJsonArray("cameras").get(0).getAsJsonObject().getAsJsonArray("keyframes").size());
        assertNull(full.get("actors"));
        assertTrue(toolError("get_project", "detail", "everything").contains("summary, full"));
    }

    @Test
    void aSavedProjectIsReadFromItsFilesWithoutChangingThem() throws IOException {
        JsonObject p = ok("get_project", "replay", "2026-11-20_10-00-00");
        assertFalse(p.getAsJsonObject("replay").get("open").getAsBoolean());
        assertEquals("Disk camera", p.getAsJsonArray("cameras").get(0).getAsJsonObject().get("name").getAsString());
        // Recording markers wait for the first opening in the game: they are listed apart.
        assertEquals("Recorded", p.getAsJsonObject("overview").getAsJsonArray("recordingMarkersNotImported").get(0)
                .getAsJsonObject().get("name").getAsString());

        Path broken = root.resolve("2026-11-19_08-00-00").resolve(ReplayStorage.PROJECT);
        assertTrue(toolError("get_project", "replay", "2026-11-19_08-00-00").contains("cannot be read"));
        assertEquals("{broken", Files.readString(broken));
        try (Stream<Path> files = Files.list(broken.getParent())) {
            assertEquals(3, files.count(), "nothing is moved aside or added");
        }
        assertTrue(toolError("get_project", "replay", "../2026-11-20_10-00-00").contains("No saved replay"));
        assertTrue(toolError("get_project", "replay", "nope").contains("No saved replay"));
    }

    @Test
    void markersComeWithTheirNotes() {
        JsonObject list = ok("list_markers");
        assertEquals(3, list.get("count").getAsInt());
        JsonArray markers = list.getAsJsonArray("markers");
        JsonObject moment = markers.get(0).getAsJsonObject();
        assertEquals("Moment", moment.get("name").getAsString());
        assertEquals("Wide shot,\nthen cut", moment.get("note").getAsString());
        assertEquals("INSTANT", moment.get("type").getAsString());
        assertEquals("0:01.5", moment.get("time").getAsString());
        JsonObject hero = markers.get(1).getAsJsonObject();
        assertEquals("ENTITY", hero.get("type").getAsString());
        assertTrue(hero.get("targetIsRecordingPlayer").getAsBoolean());
        JsonObject stretch = markers.get(2).getAsJsonObject();
        assertEquals(160, stretch.get("endTick").getAsInt());
        assertEquals("0:03.0", stretch.get("length").getAsString());

        assertEquals(1, ok("list_markers", "type", "range").get("count").getAsInt());
        assertEquals(1, ok("list_markers", "query", "THEN CUT").get("count").getAsInt());

        String id = moment.get("id").getAsString();
        JsonObject detail = ok("get_marker", "id", id).getAsJsonObject("marker");
        assertEquals("Wide shot,\nthen cut", detail.get("note").getAsString());
        assertEquals("", detail.get("previous").getAsString());
        assertEquals(hero.get("id").getAsString(), detail.get("next").getAsString());
        assertFalse(detail.get("atPlayhead").getAsBoolean());
        assertTrue(toolError("get_marker", "id", "missing").contains("no marker"));
        assertTrue(toolError("get_marker").contains("'id' is required"));

        JsonObject disk = ok("get_marker", "id", "m-disk", "replay", "2026-11-20_10-00-00").getAsJsonObject("marker");
        assertEquals("Saved note", disk.get("note").getAsString());
    }

    @Test
    void withoutAnOpenReplayTheToolsSayHowToNameOne() {
        open = null;
        state.replay.open = false;
        assertTrue(toolError("get_project").contains("No replay is open"));
        assertTrue(toolError("list_markers").contains("list_replays"));
        assertEquals("", ok("list_replays").get("openReplay").getAsString());
        JsonObject uri = new JsonObject();
        uri.addProperty("uri", McpReadTools.PROJECT_URI);
        JsonObject content = rpc("resources/read", uri).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
        assertFalse(JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject().get("open").getAsBoolean());
    }

    @Test
    void everyReadToolIsAlsoAResource() {
        JsonArray resources = rpc("resources/list", new JsonObject()).getAsJsonObject("result").getAsJsonArray("resources");
        assertEquals(List.of(McpReadTools.STATE_URI, McpReadTools.REPLAYS_URI, McpReadTools.PROJECT_URI, McpReadTools.MARKERS_URI),
                List.of(resources.get(0).getAsJsonObject().get("uri").getAsString(), resources.get(1).getAsJsonObject().get("uri").getAsString(),
                        resources.get(2).getAsJsonObject().get("uri").getAsString(), resources.get(3).getAsJsonObject().get("uri").getAsString()));
        String id = open.project().markers.get(0).id;
        JsonObject uri = new JsonObject();
        uri.addProperty("uri", "justreplay://markers/" + id);
        String text = rpc("resources/read", uri).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
        assertEquals("Moment", JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("marker").get("name").getAsString());
        uri.addProperty("uri", "justreplay://markers/none");
        assertEquals(McpException.RESOURCE_NOT_FOUND_LEGACY, rpc("resources/read", uri).getAsJsonObject("error").get("code").getAsInt());
        JsonArray tools = rpc("tools/list", new JsonObject()).getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(5, tools.size());
        for (int i = 0; i < tools.size(); i++) {
            assertTrue(tools.get(i).getAsJsonObject().getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        }
    }

    @Test
    void numbersThatAreNotFiniteBecomeNull() {
        ReplayProject.CameraKeyframe k = new ReplayProject.CameraKeyframe();
        k.x = Double.NaN;
        k.fov = Float.POSITIVE_INFINITY;
        JsonObject tree = McpJson.tree(k).getAsJsonObject();
        assertTrue(tree.get("x").isJsonNull());
        assertTrue(tree.get("fov").isJsonNull());
        assertEquals(0, tree.get("y").getAsDouble());
    }
}
