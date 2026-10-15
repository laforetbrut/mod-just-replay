package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.neoforged.api.distmarker.Dist;
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

    /** Called once when replay playback starts. */
    public static void onReplayStart(Minecraft mc) {
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.onReplayStart();
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
    }

    /** Called when the replay session closes. */
    public static void onReplayEnd() {
        if (WATERFRAMES) {
            WaterFramesCompat.clear();
        }
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.onReplayEnd();
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
