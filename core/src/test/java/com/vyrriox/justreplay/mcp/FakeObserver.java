package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A replay world for the observation tools: a pig walking east one block every 10 ticks, a villager standing still,
 * the recording player going round a circle, an actor, a horse ridden by a zombie, and as many extra zombies as a
 * test wants. Everything follows the play head, which only {@link #seek} moves.
 *
 * @author vyrriox
 */
final class FakeObserver implements McpObserver {
    static final String PIG_UUID = "00000000-0000-0000-0000-00000000a001";
    static final String VILLAGER_UUID = "00000000-0000-0000-0000-00000000a002";
    static final String RECORDER_UUID = "00000000-0000-0000-0000-00000000a003";

    int cursor = 40;
    int duration = 1200;
    boolean paused = true;
    boolean exporting;
    boolean open = true;
    int extraZombies;
    String longName = "";
    int[] window = {1920, 1080};
    final List<Integer> seeks = new ArrayList<>();
    final List<FrameRequest> frames = new ArrayList<>();
    /** When set, render answers with a future nobody completes. */
    boolean holdFrames;
    CompletableFuture<Frame> heldFrame;
    final ReplayProject project = new ReplayProject();
    final ReplayMeta meta = new ReplayMeta();
    final Marker spot;
    final Marker hero;
    final Marker moment;

    FakeObserver() {
        meta.name = "Fake";
        meta.durationTicks = duration;
        meta.recorderName = "Alex";
        meta.recorderUuid = RECORDER_UUID;
        spot = project.addMarker(Marker.point(10, 12.0, 64.0, 5.0, "minecraft:overworld", "Pig pen"));
        hero = project.addMarker(Marker.entity(20, Marker.RECORDER, "Alex", "Hero"));
        moment = project.addMarker(Marker.instant(30, "Moment"));
        project.normalize();
    }

    /** Gives the project one camera with keyframes, so that it has a camera path. */
    void addCamera() {
        ReplayProject.Camera camera = new ReplayProject.Camera();
        camera.id = "cam";
        camera.name = "Path";
        for (int tick : new int[]{0, 600}) {
            ReplayProject.CameraKeyframe k = new ReplayProject.CameraKeyframe();
            k.tick = tick;
            camera.keyframes.add(k);
        }
        project.cameras.add(camera);
        project.normalize();
    }

    @Override
    public Replay replay() {
        return open ? new Replay("fake-replay", meta, project, cursor, duration, paused, 1.0, exporting) : null;
    }

    @Override
    public int seek(int tick) {
        seeks.add(tick);
        cursor = Math.max(0, Math.min(duration, tick));
        return cursor;
    }

    @Override
    public List<McpEntity> entities() {
        List<McpEntity> list = new ArrayList<>();
        McpEntity pig = living(1, PIG_UUID, "minecraft:pig", "Pig", 10.0 + cursor / 10.0, 64.0, 5.0);
        // What the game keeps for a recorded entity is often not its real motion.
        pig.vx = 0.5;
        list.add(pig);
        list.add(living(2, VILLAGER_UUID, "minecraft:villager", "Villager", -20.0, 64.0, 30.0));
        McpEntity alex = living(3, RECORDER_UUID, "minecraft:player", "Alex", 5.0 * Math.cos(cursor / 20.0), 64.0,
                5.0 * Math.sin(cursor / 20.0));
        alex.recorder = true;
        alex.player = true;
        alex.equipment.add(new McpEntity.Item("mainhand", "minecraft:diamond_sword", 1, "Excalibur"));
        list.add(alex);
        McpEntity actor = living(-1000, "00000000-0000-0000-0000-00000000b001", "minecraft:player", "Steve", 0.0, 65.0, 0.0);
        actor.source = McpEntity.ACTOR;
        actor.actorId = "actor-1";
        actor.player = true;
        list.add(actor);
        McpEntity horse = living(10, "00000000-0000-0000-0000-00000000c001", "minecraft:horse", "Horse", 40.0, 64.0, 40.0);
        horse.passengers.add(11);
        list.add(horse);
        McpEntity rider = living(11, "00000000-0000-0000-0000-00000000c002", "minecraft:zombie", "Zombie", 40.0, 65.0, 40.0);
        rider.vehicle = 10;
        list.add(rider);
        for (int i = 0; i < extraZombies; i++) {
            McpEntity z = living(100 + i, String.format("00000000-0000-0000-0000-%012d", 100 + i), "minecraft:zombie",
                    longName.isEmpty() ? "Zombie" : longName, 100.0 + i, 64.0, 100.0);
            z.customName = longName;
            for (int s = 0; s < 20; s++) {
                z.equipment.add(new McpEntity.Item("slot" + s, "minecraft:stick", 64, longName));
            }
            list.add(z);
        }
        return list;
    }

    private static McpEntity living(int id, String uuid, String type, String name, double x, double y, double z) {
        McpEntity e = new McpEntity();
        e.id = id;
        e.uuid = uuid;
        e.type = type;
        e.name = name;
        e.living = true;
        e.x = x;
        e.y = y;
        e.z = z;
        e.health = 10.0F;
        e.maxHealth = 20.0F;
        e.width = 0.6;
        e.height = 1.8;
        e.eyeHeight = 1.62;
        e.pose = "STANDING";
        return e;
    }

    @Override
    public McpEntity entity(String key) {
        for (McpEntity e : entities()) {
            if (e.matches(key)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public World world() {
        World w = new World();
        w.dimension = "minecraft:overworld";
        w.dayTime = 1000L + cursor;
        w.recordedDayTime = 1000L + cursor;
        w.gameTime = 50_000L + cursor;
        w.raining = true;
        w.rainLevel = 1.0F;
        w.difficulty = "normal";
        w.entities = entities().size();
        w.players = 2;
        w.loadedChunks = 441;
        return w;
    }

    @Override
    public Block block(int x, int y, int z) {
        Block b = new Block();
        b.loaded = Math.abs(x) < 1000 && Math.abs(z) < 1000;
        if (b.loaded) {
            b.air = y >= 64;
            b.id = b.air ? "minecraft:air" : "minecraft:oak_log";
            Map<String, String> properties = new LinkedHashMap<>();
            if (!b.air) {
                properties.put("axis", "y");
            }
            b.properties = properties;
            b.biome = "minecraft:plains";
            b.blockLight = 0;
            b.skyLight = 15;
        }
        return b;
    }

    @Override
    public List<Player> tabList() {
        return List.of(new Player("Alex", RECORDER_UUID, "survival", 12), new Player("Sam", "00000000-0000-0000-0000-00000000d001",
                "creative", 40));
    }

    @Override
    public McpCamera currentCamera() {
        return new McpCamera(0.0, 70.0, 0.0, 0.0, 0.0, 0.0, 70.0, "current");
    }

    @Override
    public McpCamera projectCamera(int tick) {
        boolean keyframes = project.cameras.stream().anyMatch(c -> !c.keyframes.isEmpty());
        return keyframes ? new McpCamera(tick / 10.0, 80.0, -10.0, 0.0, 20.0, 0.0, 60.0, "project") : null;
    }

    @Override
    public int[] windowSize() {
        return window.clone();
    }

    @Override
    public CompletableFuture<Frame> render(FrameRequest request) throws McpArgs.Invalid {
        frames.add(request);
        if (holdFrames) {
            heldFrame = new CompletableFuture<>();
            return heldFrame;
        }
        int w = request.ui() ? window[0] : request.width();
        int h = request.ui() ? window[1] : request.height();
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            rgba[i * 4] = (byte) 200;
            rgba[i * 4 + 1] = (byte) 100;
            rgba[i * 4 + 2] = (byte) 50;
            rgba[i * 4 + 3] = (byte) 255;
        }
        McpCamera used = request.camera() != null ? request.camera() : request.ui() ? currentCamera() : projectCamera(request.tick());
        return CompletableFuture.completedFuture(new Frame(rgba, w, h, request.tick(), used == null ? currentCamera() : used));
    }
}
