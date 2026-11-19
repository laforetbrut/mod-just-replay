package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// The integrations (CURIOS, JADE...): ReplayCompat forwards each hook through the one it belongs to.
import static com.vyrriox.justreplay.compat.Integrations.*;

/**
 * Entry point of the optional mod integrations: the replay session, the playback and the recorder call these hooks,
 * which forward them to each integration whose mod is installed. Every integration reaches its mod by reflection
 * (or through optional mixins), so nothing here loads a class of a missing mod. Only the integrations listed in
 * {@link CompatTargets} for the running loader are active.
 * <p>
 * Each call into an integration goes through its {@link Integration} (see {@link Integrations}): it runs only while
 * the integration is on, and any error it throws (a {@link LinkageError} from a changed mod included) turns that
 * integration off for the rest of the game with one warning, instead of crashing the game, the recording or the
 * replay. Hooks that put back what an integration changed in its mod still run after such a failure
 * ({@link Integration#cleanup}).
 *
 * @author vyrriox
 */
public final class ReplayCompat {
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());
    private static double monotonicFrame = -1;
    private static double monotonicValue;
    private static double lastGeckoEntityFrameTime = -1;

    private ReplayCompat() {
    }

    /** Logs an integration failure once per key, then the integration keeps going without that feature. */
    static void warnOnce(String key, String message, Throwable t) {
        if (FAILED.add(key)) {
            JustReplay.LOGGER.warn("{}: {}", message, t == null ? "" : t.toString());
        }
    }

    /**
     * Logs once an error of a compat mixin that adjusts the game itself for a mod (the level renderer clock, the section
     * draw guard): that adjustment is skipped and the game keeps its own behaviour.
     */
    public static void warnOnceGame(String hook, Throwable t) {
        warnOnce("game." + hook, "Just Replay cannot adjust " + hook + " during replays, it keeps the game's behaviour", t);
    }

    // ---------------------------------------------------------------- replay clock

    /** The running replay's playback, or null outside a replay. */
    static Playback playback() {
        ReplaySession session = ReplaySession.current();
        return session != null && ReplaySession.isRunning() ? session.playback() : null;
    }

    /**
     * Animation ticks a client tick should advance during a replay: 0 while paused, several above x1 (the client never
     * ticks faster than 20 TPS, the playback plays several recorded ticks per client tick instead). -1 outside a replay.
     */
    public static int animationSteps() {
        Playback playback = playback();
        if (playback == null) {
            return -1;
        }
        if (playback.isPaused() || Minecraft.getInstance().isPaused()) {
            return 0;
        }
        return Math.max(1, Math.round(Math.max(1.0F, playback.speed())));
    }

    /** True while a replay is paused: client-side animations hold their pose. */
    public static boolean isReplayPaused() {
        Playback playback = playback();
        return playback != null && playback.isPaused();
    }

    /**
     * Value for client animation tick counters (the level renderer clock used by Flywheel) during a replay: the replay
     * cursor, so they pause, follow the speed, and show the same state after a seek or in an export started anywhere.
     * -1 outside a replay.
     */
    public static int animationTicks() {
        Playback playback = playback();
        return playback == null ? -1 : playback.cursor() % 1728000;
    }

    /**
     * Replay time of the frame being rendered, in ticks with its fraction: holds while paused, follows the speed and is
     * exact per export frame. Goes back after a backward seek. -1 outside a replay.
     */
    public static double frameClockTicks() {
        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning()) {
            return -1;
        }
        return com.vyrriox.justreplay.editor.CameraController.frameTime(session,
                com.vyrriox.justreplay.client.Frames.partialTick());
    }

    /**
     * {@link #frameClockTicks()} for animation systems that hold when their clock goes back: it adds the rises of the
     * frame clock and never decreases, a backward seek only stops counting. It starts at the wall clock, or above if an
     * earlier replay ran faster, because animations started before the replay hold their start time. -1 outside a
     * replay. Render thread only.
     */
    public static double monotonicAnimTicks() {
        double now = frameClockTicks();
        if (now < 0) {
            monotonicFrame = -1;
            return -1;
        }
        if (monotonicFrame < 0) {
            monotonicValue = Math.max(monotonicValue, com.mojang.blaze3d.Blaze3D.getTime() * 20.0);
            monotonicFrame = now;
        } else if (now > monotonicFrame) {
            monotonicValue += now - monotonicFrame;
            monotonicFrame = now;
        } else if (monotonicFrame - now > Math.max(2, playback().lastStep() + 1)) {
            // A real backward seek. Smaller drops come from resuming a pause, which replays the last step's fraction.
            monotonicFrame = now;
        }
        return monotonicValue;
    }

    /** Replaces the frame time of a GeckoLib entity during a replay, which otherwise follows its tick counter. */
    public static double geckoEntityFrameTime(double original) {
        double ticks = monotonicAnimTicks();
        if (ticks < 0) {
            return original;
        }
        lastGeckoEntityFrameTime = ticks;
        return ticks;
    }

    /** The last frame time given to a GeckoLib entity, -1 if none was: no such entity rendered yet. */
    public static double lastGeckoEntityFrameTime() {
        return lastGeckoEntityFrameTime;
    }

    // ---------------------------------------------------------------- session hooks

    /**
     * Called off the render thread when a replay is about to open, before its world is created: integrations copy or
     * prepare what the replay world needs (Distant Horizons' recorded LODs).
     */
    public static void prepareReplay(com.vyrriox.justreplay.format.ReplayMeta meta) {
        DISTANT_HORIZONS.run("prepare", () -> DistantHorizonsCompat.prepare(meta));
    }

    /** Called once the replay world has fully closed (its integrated server stopped), or after it failed to open. */
    public static void onReplayWorldClosed() {
        DISTANT_HORIZONS.cleanup("onReplayWorldClosed", DistantHorizonsCompat::onReplayWorldClosed);
    }

    /** Called once when replay playback starts. */
    public static void onReplayStart(Minecraft mc) {
        DISTANT_HORIZONS.run("onReplayStart", DistantHorizonsCompat::onReplayStart);
        ENTITY_CULLING.run("onReplayStart", EntityCullingCompat::onReplayStart);
    }

    /** Called every client tick, replay or not. */
    public static void clientTick(Minecraft mc) {
        if (DISTANT_HORIZONS.on() && !ReplaySession.isRunning()) {
            DISTANT_HORIZONS.run("recoverAfterInterruptedReplay", DistantHorizonsCompat::recoverAfterInterruptedReplay);
        }
    }

    /** Called every client tick of a running replay, after the playback tick. */
    public static void tick(Minecraft mc, ReplaySession session) {
        IMMERSIVE_VEHICLES.run("tick", () -> ImmersiveVehiclesCompat.tick(mc));
        WATERFRAMES.run("tick", () -> WaterFramesCompat.tick(mc, session.playback()));
        DISTANT_HORIZONS.run("tick", DistantHorizonsCompat::tick);
        ENTITY_CULLING.run("tick", EntityCullingCompat::tick);
    }

    /** Called when the replay session closes. Runs for integrations that failed too: they put back what they changed. */
    public static void onReplayEnd() {
        monotonicFrame = -1;
        ENTITY_CULLING.cleanup("onReplayEnd", EntityCullingCompat::onReplayEnd);
        WATERFRAMES.cleanup("onReplayEnd", WaterFramesCompat::clear);
        DISTANT_HORIZONS.cleanup("onReplayEnd", DistantHorizonsCompat::onReplayEnd);
        IMMERSIVE_VEHICLES.cleanup("onReplayEnd", ImmersiveVehiclesCompat::onReplayEnd);
    }

    /**
     * A recorded packet about to be handled, after the generic filters: returns the packet to handle in its place, or
     * null to drop it. A failing integration leaves the packet as it was. Called for every packet: no allocation.
     */
    public static Packet<?> filterPlayback(Packet<?> packet) {
        if (packet != null && JADE.on()) {
            try {
                packet = JadeCompat.filterPlayback(packet);
            } catch (Throwable t) {
                JADE.fail("filterPlayback", t);
            }
        }
        return packet;
    }

    /** A packet the client is about to send to the replay host: true to drop it (requests of mods with no server there). */
    public static boolean blockServerbound(Packet<?> packet) {
        return JADE.on() && JADE.test("blockServerbound", () -> JadeCompat.blockServerbound(packet))
                || IMMERSIVE_VEHICLES.on() && IMMERSIVE_VEHICLES.test("blockServerbound", () -> ImmersiveVehiclesCompat.blockServerbound(packet));
    }

    // ---------------------------------------------------------------- recording hooks

    /**
     * Notes in a recording's meta what integrations need to know about the level being recorded (the Distant Horizons
     * LOD folder of its dimension). Called every client tick of a recording, and with {@code force} when a meta is made.
     */
    public static void describeLevel(com.vyrriox.justreplay.format.ReplayMeta meta, net.minecraft.world.level.Level level,
                                     boolean force) {
        if (DISTANT_HORIZONS.on()) {
            DISTANT_HORIZONS.run("describe", () -> DistantHorizonsCompat.describe(meta, level, force));
        }
    }

    /** Mod definitions the entity state depends on (snapshots and checkpoints), written before any entity. */
    public static void writeDefinitions(java.util.function.Consumer<Packet<?>> out) {
        CURIOS.run("writeDefinitions", () -> CuriosCompat.writeDefinitions(out));
    }

    /** Mod state a player is in now (snapshots and checkpoints), written as another client receives it. */
    public static void writePlayerState(net.minecraft.world.entity.player.Player player,
                                        java.util.function.Consumer<Packet<?>> out) {
        CURIOS.run("writeCurrent", () -> CuriosCompat.writeCurrent(player, out));
    }

    /** Mod state an entity other than a player carries now (snapshots and checkpoints), written after its spawn. */
    public static void writeEntityState(net.minecraft.world.entity.Entity entity, java.util.function.Consumer<Packet<?>> out) {
        if (CURIOS.on()) {
            CURIOS.run("writeCurrent", () -> CuriosCompat.writeCurrent(entity, out));
        }
        if (IMMERSIVE_VEHICLES.on()) {
            IMMERSIVE_VEHICLES.run("writeCurrent", () -> ImmersiveVehiclesCompat.writeCurrent(entity, out));
        }
    }

    /** Mod state of the blocks of a chunk just written into a snapshot or checkpoint (Immersive Vehicles blocks). */
    public static void writeChunkState(net.minecraft.world.level.chunk.LevelChunk chunk,
                                       java.util.function.Consumer<Packet<?>> out) {
        if (IMMERSIVE_VEHICLES.on()) {
            IMMERSIVE_VEHICLES.run("writeChunk", () -> ImmersiveVehiclesCompat.writeChunk(chunk, out));
        }
    }

    /** Mod state the recording player's actor needs right after it spawns (the server sent it before the capture). */
    public static void writeActorForm(net.minecraft.world.entity.player.Player player,
                                      java.util.function.Consumer<Packet<?>> out) {
        CURIOS.run("writeCurrent", () -> CuriosCompat.writeCurrent(player, out));
    }

    // ---------------------------------------------------------------- export hooks

    public static final class Events {
        private Events() {
        }

        /** Exports render frames faster or slower than real time: in-world videos are moved to each frame's time. */
        public static void onRenderFrame(float partialTick) {
            if (!WATERFRAMES.on() || !VideoExporter.isActive()) {
                return;
            }
            Playback playback = playback();
            if (playback == null) {
                return;
            }
            try {
                WaterFramesCompat.syncFrame(playback, partialTick);
            } catch (Throwable t) {
                WATERFRAMES.fail("syncFrame", t);
            }
        }
    }
}
