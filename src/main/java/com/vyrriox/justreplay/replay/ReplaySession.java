package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.RecordedPacket;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A replay being watched. The client joins a throw-away void world hosted by the integrated server, so login,
 * configuration and mod network negotiation happen exactly as in a normal game; once in, every packet from
 * that server is blocked and the recorded packets are played instead.
 *
 * @author vyrriox
 */
public final class ReplaySession {
    public static final String WORLD_NAME = "justreplay-session";

    private static ReplaySession current;
    private static boolean pendingWorldCleanup;

    private enum State { WAITING_LOGIN, STARTING, RUNNING }

    private final Path dir;
    private final ReplayMeta meta;
    private final ReplayProject project;
    private final Playback playback;
    private final ActorManager actors;
    private final SpectateCamera spectate = new SpectateCamera();
    private final UUID actorUuid;
    private volatile Connection connection;
    private volatile boolean blocking;
    private State state = State.WAITING_LOGIN;
    private boolean cameraPath;
    private boolean hudVisible = true;
    private boolean cameraPlaced;

    private ReplaySession(Path dir, ReplayMeta meta, ReplayProject project, com.vyrriox.justreplay.format.PacketStream packets) {
        this.dir = dir;
        this.meta = meta;
        this.project = project;
        this.playback = new Playback(packets, meta.durationTicks);
        this.actors = new ActorManager(this);
        UUID uuid = null;
        try {
            uuid = UUID.fromString(meta.actorUuid);
        } catch (IllegalArgumentException ignored) {
        }
        this.actorUuid = uuid;
    }

    public static ReplaySession current() {
        return current;
    }

    public static boolean isRunning() {
        return current != null && current.state == State.RUNNING;
    }

    public static boolean isReplayConnection(Connection connection) {
        ReplaySession s = current;
        return s != null && connection != null && s.connection == connection;
    }

    /** True when a packet arriving on this connection comes from the host server and must be ignored. */
    public static boolean shouldBlock(Connection connection) {
        ReplaySession s = current;
        return s != null && s.blocking && s.connection == connection;
    }

    /** True for the integrated server that hosts the replay's empty world. */
    public static boolean isHostServer(net.minecraft.server.MinecraftServer server) {
        return current != null && server != null && WORLD_NAME.equals(server.getWorldData().getLevelName());
    }

    public Path dir() {
        return dir;
    }

    public ReplayMeta meta() {
        return meta;
    }

    public ReplayProject project() {
        return project;
    }

    public Playback playback() {
        return playback;
    }

    public ActorManager actors() {
        return actors;
    }

    public SpectateCamera spectate() {
        return spectate;
    }

    public boolean isCameraPathActive() {
        return cameraPath || VideoExporter.isActive();
    }

    public void setCameraPath(boolean active) {
        this.cameraPath = active;
        if (active) {
            // The path drives the free camera; viewing through an entity would hide it.
            spectate.stop(Minecraft.getInstance());
        }
    }

    public boolean isHudVisible() {
        return hudVisible;
    }

    public void setHudVisible(boolean visible) {
        this.hudVisible = visible;
    }

    public void saveProject() {
        project.sort();
        ReplayStorage.writeProject(dir, project);
    }

    // ---------------------------------------------------------------- launch

    public static void open(Path dir, Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (current != null || mc.level != null) {
            return;
        }
        if (loading) {
            return;
        }
        loading = true;
        mc.forceSetScreen(new GenericMessageScreen(Component.translatable("justreplay.loading")));
        // Decompressing a long replay takes a while: do it off the render thread.
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                com.vyrriox.justreplay.format.PacketStream stream = com.vyrriox.justreplay.format.PacketStream.open(dir);
                stream.lastTick(); // reads the last segment here rather than on the render thread
                return stream;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((packets, error) -> {
            loading = false;
            if (error != null) {
                JustReplay.LOGGER.error("Cannot read replay {}", dir, error);
                net.minecraft.client.gui.components.toasts.SystemToast.add(mc.getToasts(),
                        net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("justreplay.toast.open_failed"), Component.literal(dir.getFileName().toString()));
                mc.setScreen(parent);
                return;
            }
            start(mc, dir, parent, packets);
        }, mc);
    }

    private static boolean loading;

    private static void start(Minecraft mc, Path dir, Screen parent, com.vyrriox.justreplay.format.PacketStream packets) {
        if (packets.isEmpty()) {
            JustReplay.LOGGER.warn("Replay {} is empty", dir);
            mc.setScreen(parent);
            return;
        }
        current = new ReplaySession(dir, ReplayStorage.readMeta(dir), ReplayStorage.readProject(dir), packets);
        JustReplay.LOGGER.info("Opening replay {} ({} ticks)", dir.getFileName(), packets.lastTick());
        deleteSessionWorld(mc);

        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules,
                WorldDataConfiguration.DEFAULT);
        try {
            mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, new WorldOptions(0L, false, false),
                    ReplaySession::voidDimensions, parent);
            if (mc.getSingleplayerServer() == null) {
                // World creation failed and returned to the menu: forget the session so the next open works.
                current = null;
                pendingWorldCleanup = true;
                JustReplay.LOGGER.error("The replay world could not be started");
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot start the replay world", t);
            current = null;
            mc.setScreen(parent);
        }
    }

    private static WorldDimensions voidDimensions(RegistryAccess access) {
        WorldDimensions dimensions = access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                .value().createWorldDimensions();
        Holder<Biome> voidBiome = access.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), voidBiome, List.of());
        return dimensions.replaceOverworldGenerator(access, new FlatLevelSource(flat));
    }

    private static void deleteSessionWorld(Minecraft mc) {
        LevelStorageSource source = mc.getLevelSource();
        if (!source.levelExists(WORLD_NAME)) {
            return;
        }
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(WORLD_NAME)) {
            access.deleteLevel();
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot delete the previous replay world", e);
        }
    }

    // ---------------------------------------------------------------- events

    public static void onLoggingIn(Connection connection) {
        ReplaySession s = current;
        JustReplay.LOGGER.debug("Login event, session={}, state={}", s, s == null ? null : s.state);
        if (s != null && s.state == State.WAITING_LOGIN) {
            s.connection = connection;
            s.blocking = true;
            s.state = State.STARTING;
            JustReplay.LOGGER.info("Replay connection established, playback starts next tick");
        }
    }

    public static void onLoggingOut() {
        ReplaySession s = current;
        if (s != null) {
            s.spectate.clear();
            VideoExporter.cancel();
            s.saveProject();
            current = null;
            pendingWorldCleanup = true;
        }
    }

    public static void clientTick(Minecraft mc) {
        if (pendingWorldCleanup && mc.level == null && mc.getSingleplayerServer() == null) {
            pendingWorldCleanup = false;
            deleteSessionWorld(mc);
        }
        ReplaySession s = current;
        if (s != null && s.state == State.RUNNING && mc.level == null) {
            onLoggingOut();
            return;
        }
        if (s == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (s.state == State.STARTING) {
            s.state = State.RUNNING;
            // Recorded ids must be in place before the codec decodes anything.
            boolean ids = com.vyrriox.justreplay.format.RegistryIds.apply(s.dir);
            JustReplay.LOGGER.info("Registry ids from the recording: {}", ids ? "applied" : "none (older replay)");
            s.playback.start(mc.getConnection());
            JustReplay.LOGGER.info("Replay playback started: {} ticks", s.playback.duration());
        }
        if (s.state != State.RUNNING) {
            return;
        }
        s.playback.tick(mc);
        Playback.enforceCamera(mc);
        if (mc.screen instanceof ReceivingLevelScreen) {
            mc.setScreen(null);
        }
        s.playback.consumeWorldReset();
        if (!s.cameraPlaced) {
            s.placeCameraOnRecorder(mc);
        }
        s.actors.tick(mc);
        s.spectate.tick(mc, s);
    }

    private void placeCameraOnRecorder(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        Entity recorder = findRecorder(mc);
        if (recorder != null) {
            Playback.placeCamera(player, recorder.position().add(0, recorder.getEyeHeight() - player.getEyeHeight(), 0),
                    recorder.getYRot(), recorder.getXRot());
            cameraPlaced = true;
        } else if (playback.cursor() > 40) {
            cameraPlaced = true;
        }
    }

    public Entity findRecorder(Minecraft mc) {
        if (actorUuid == null || mc.level == null) {
            return null;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (actorUuid.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    /** Moves the camera to the recording player's point of view. */
    public void jumpToRecorder(Minecraft mc) {
        spectate.stop(mc);
        Entity recorder = findRecorder(mc);
        if (recorder != null && mc.player != null) {
            Vec3 eye = recorder.position().add(0, recorder.getEyeHeight() - mc.player.getEyeHeight(), 0);
            Playback.placeCamera(mc.player, eye, recorder.getYRot(), recorder.getXRot());
        }
    }

    /** Follows the recording player in first person, or returns to the free camera when already following it. */
    public void toggleRecorderView(Minecraft mc) {
        if (spectate.isFollowingRecorder()) {
            spectate.stop(mc);
            return;
        }
        if (isCameraPathActive()) {
            return;
        }
        Entity recorder = findRecorder(mc);
        if (recorder != null) {
            spectate.start(mc, recorder, true);
        }
    }

    /** Views the replay through an entity, unless the camera path or a video export drives the camera. */
    public void spectate(Minecraft mc, Entity entity) {
        if (entity == null || isCameraPathActive()) {
            return;
        }
        spectate.start(mc, entity, actorUuid != null && actorUuid.equals(entity.getUUID()));
    }
}
