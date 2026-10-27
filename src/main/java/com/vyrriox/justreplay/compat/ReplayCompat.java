package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Entry point of the optional mod integrations: the replay session, the playback and the recorder call these hooks,
 * which forward them to each integration whose mod is installed. Every integration reaches its mod by reflection
 * (or through optional mixins), so nothing here loads a class of a missing mod.
 *
 * @author vyrriox
 */
public final class ReplayCompat {
    static final boolean WATERFRAMES = isLoaded("waterframes");
    static final boolean EMOTECRAFT = isLoaded("emotecraft");
    static final boolean DISTANT_HORIZONS = isLoaded("distanthorizons");
    static final boolean DROP_THE_BEAM = isLoaded("dropthebeam");
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    private ReplayCompat() {
    }

    private static boolean isLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Logs an integration failure once per key, then the integration keeps going without that feature. */
    static void warnOnce(String key, String message, Throwable t) {
        if (FAILED.add(key)) {
            JustReplay.LOGGER.warn("{}: {}", message, t == null ? "" : t.toString());
        }
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
     * Value for client animation tick counters (Create's animation clock, the level renderer clock used by Flywheel)
     * during a replay: the replay cursor, so they pause, follow the speed, and show the same state after a seek or in
     * an export started anywhere. -1 outside a replay.
     */
    public static int animationTicks() {
        Playback playback = playback();
        return playback == null ? -1 : playback.cursor() % 1728000;
    }

    // ---------------------------------------------------------------- session hooks

    /**
     * Called off the render thread when a replay is about to open, before its world is created: integrations copy or
     * prepare what the replay world needs (Distant Horizons' recorded LODs).
     */
    public static void prepareReplay(com.vyrriox.justreplay.format.ReplayMeta meta) {
        if (DISTANT_HORIZONS) {
            try {
                DistantHorizonsCompat.prepare(meta);
            } catch (Throwable t) {
                warnOnce("dh.prepare", "Cannot prepare the Distant Horizons LODs for the replay", t);
            }
        }
    }

    /** Called once the replay world has fully closed (its integrated server stopped), or after it failed to open. */
    public static void onReplayWorldClosed() {
        if (DISTANT_HORIZONS) {
            try {
                DistantHorizonsCompat.onReplayWorldClosed();
            } catch (Throwable t) {
                warnOnce("dh.closed", "Cannot clean up the Distant Horizons replay data", t);
            }
        }
    }

    /** Called once when replay playback starts. */
    public static void onReplayStart(Minecraft mc) {
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.onReplayStart();
        }
        if (DROP_THE_BEAM) {
            ReplaySession session = ReplaySession.current();
            DropTheBeamCompat.onReplayStart(session != null ? session.dir() : null);
        }
    }

    /** Called every client tick, replay or not. */
    public static void clientTick(Minecraft mc) {
        if (DISTANT_HORIZONS && !ReplaySession.isRunning()) {
            DistantHorizonsCompat.recoverAfterInterruptedReplay();
        }
    }

    /** Called every client tick of a running replay, after the playback tick. */
    public static void tick(Minecraft mc, ReplaySession session) {
        if (WATERFRAMES) {
            try {
                WaterFramesCompat.tick(mc, session.playback());
            } catch (Throwable t) {
                warnOnce("waterframes.tick", "WaterFrames sync failed", t);
            }
        }
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.tick();
        }
        if (DROP_THE_BEAM) {
            try {
                DropTheBeamCompat.tick(mc, session);
            } catch (Throwable t) {
                warnOnce("dropthebeam.tick", "Drop the Beam sync failed", t);
            }
        }
    }

    /** Called when the replay session closes. */
    public static void onReplayEnd() {
        if (WATERFRAMES) {
            WaterFramesCompat.clear();
        }
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.onReplayEnd();
        }
        if (DROP_THE_BEAM) {
            DropTheBeamCompat.onReplayEnd();
        }
    }

    /**
     * A recorded packet about to be handled at its recorded tick (seeks included): true when an integration consumed
     * it and it must not reach the client (Drop the Beam's clock packets, which belong to the recording process).
     */
    public static boolean consumeRecorded(Packet<?> packet, int tick) {
        return DROP_THE_BEAM && DropTheBeamCompat.consumeRecorded(packet, tick);
    }

    /** A packet the client is about to send to the replay host: true to drop it (requests of mods with no server there). */
    public static boolean blockServerbound(Packet<?> packet) {
        return DROP_THE_BEAM && DropTheBeamCompat.blockServerbound(packet);
    }

    // ---------------------------------------------------------------- recording hooks

    /**
     * Notes in a recording's meta what integrations need to know about the level being recorded (the Distant Horizons
     * LOD folder of its dimension). Called every client tick of a recording, and with {@code force} when a meta is made.
     */
    public static void describeLevel(com.vyrriox.justreplay.format.ReplayMeta meta, net.minecraft.world.level.Level level,
                                     boolean force) {
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.describe(meta, level, force);
        }
    }

    /** A clientbound packet of a live (recorded) connection, on the network thread; {@code newConnection} on its first. */
    public static void onLivePacket(Packet<?> packet, boolean newConnection) {
        if (!DROP_THE_BEAM) {
            return;
        }
        if (newConnection) {
            DropTheBeamCompat.clearCache();
        }
        DropTheBeamCompat.track(packet);
    }

    /** Mod state a snapshot or checkpoint must write because the server sends it only on a change. */
    public static void writeSnapshot(java.util.function.Consumer<Packet<?>> out) {
        if (DROP_THE_BEAM) {
            try {
                DropTheBeamCompat.writeSnapshot(out);
            } catch (Throwable t) {
                warnOnce("dropthebeam.snapshot", "Cannot write the Drop the Beam state into the snapshot", t);
            }
        }
    }

    /** A synthetic packet to record on this capture tick (a mod clock sample), or null. */
    public static Packet<?> recordTick(int captureTick) {
        return DROP_THE_BEAM ? DropTheBeamCompat.sampleForTick(captureTick) : null;
    }

    // ---------------------------------------------------------------- export hooks

    /** An export starts (before its pre-roll). */
    public static void onExportStart() {
        if (DROP_THE_BEAM) {
            DropTheBeamCompat.beginExport();
        }
    }

    /** An export ended, finished or not. */
    public static void onExportEnd() {
        if (DROP_THE_BEAM) {
            DropTheBeamCompat.endExport();
        }
    }

    /** A frame was captured: integrations with their own audio engine render its stretch into the export mix. */
    public static void onExportFrame(com.vyrriox.justreplay.export.ExportAudio audio, int frame, int fps, double x, double y, double z,
                                     float yaw, float partialTick) {
        if (DROP_THE_BEAM) {
            try {
                DropTheBeamCompat.onExportFrame(audio, frame, fps, x, y, z, yaw, partialTick);
            } catch (Throwable t) {
                warnOnce("dropthebeam.export", "Cannot render Drop the Beam's audio for the export", t);
            }
        }
    }

    /**
     * A recorded packet played during a seek, {@code late} ticks before the seek target. Returns the packet to handle
     * in its place, or null to drop it (an emote that has already ended at the target).
     */
    public static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (EMOTECRAFT && late > 0) {
            return EmotecraftCompat.adjustSkipped(packet, late);
        }
        return packet;
    }

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        /** Before anything renders (mods read their clock while rendering): integration clocks move to this frame. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onRenderFrameFirst(RenderFrameEvent.Pre event) {
            if (!DROP_THE_BEAM) {
                return;
            }
            try {
                DropTheBeamCompat.onFrame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } catch (Throwable t) {
                warnOnce("dropthebeam.frame", "Drop the Beam clock sync failed", t);
            }
        }

        /** Exports render frames faster or slower than real time: in-world videos are moved to each frame's time. */
        @SubscribeEvent
        public static void onRenderFrame(RenderFrameEvent.Pre event) {
            if (!WATERFRAMES || !VideoExporter.isActive()) {
                return;
            }
            Playback playback = playback();
            if (playback == null) {
                return;
            }
            try {
                WaterFramesCompat.syncFrame(playback, event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } catch (Throwable t) {
                warnOnce("waterframes.frame", "WaterFrames export sync failed", t);
            }
        }
    }
}
