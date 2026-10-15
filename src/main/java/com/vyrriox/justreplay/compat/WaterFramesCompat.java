package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * WaterFrames displays (frames, TVs, projectors) during a replay. WaterFrames keeps the play time of a display in its
 * block entity data ({@code DisplayData.tick}, synced by the server) and drives a WaterMedia video player from it,
 * which plays on the wall clock. In a replay the data tick is rebuilt from the replay cursor: each time the recorded
 * stream loads display data (chunk, block entity update or WaterFrames control packet), the tick it carried is
 * anchored to the recorded tick of that packet, and the display time follows the cursor from there. The video player
 * then follows that time: paused with the replay, played at the replay speed, moved after a seek, and, during an
 * export, held paused and moved to the exact time of every rendered frame with its sound muted (the exported sound
 * track is built from game sounds only).
 * <p>
 * Everything is reached by reflection: nothing here loads a WaterFrames class.
 *
 * @author vyrriox
 */
public final class WaterFramesCompat {
    private static final String TILE = "me.srrapero720.waterframes.common.block.entity.DisplayTile";
    private static final String DATA = "me.srrapero720.waterframes.common.block.data.DisplayData";
    private static final String DISPLAY = "me.srrapero720.waterframes.client.display.Display";
    private static final String PLAYER = "org.watermedia.api.player.videolan.BasePlayer";
    /** Drift tolerated between the replay time and the video before it is moved, in milliseconds at x1. */
    private static final long MAX_DRIFT_MS = 1000;

    private static final Map<BlockEntity, Track> TRACKS = new WeakHashMap<>();
    private static Reflect reflect;
    private static boolean reflectFailed;

    /** Where a display's time comes from: its data tick at a recorded tick. */
    private static final class Track {
        int baseTick;
        int baseReplayTick;
        int lastSeekCount = -1;
        long lastFrameTarget = Long.MIN_VALUE;
        float lastSpeed = -1.0F;
    }

    private WaterFramesCompat() {
    }

    /** Fields and methods of WaterFrames and WaterMedia, looked up once. */
    private record Reflect(Field data, Field display, Field tick, Field tickMax, Field loop, Field paused, Field active,
                           Field muted, Field mediaPlayer, Method isSafeUse, Method isValid, Method isPaused,
                           Method setPauseMode, Method getTime, Method seekTo, Method setSpeed, Method isMuted,
                           Method setMuteMode, Method isLive, Method isSeekAble) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed) {
            return reflect;
        }
        try {
            ClassLoader loader = WaterFramesCompat.class.getClassLoader();
            Class<?> tile = Class.forName(TILE, false, loader);
            Class<?> data = Class.forName(DATA, false, loader);
            Class<?> display = Class.forName(DISPLAY, false, loader);
            Class<?> player = Class.forName(PLAYER, false, loader);
            Field mediaPlayer = display.getDeclaredField("mediaPlayer");
            mediaPlayer.setAccessible(true);
            reflect = new Reflect(tile.getField("data"), tile.getField("display"), data.getField("tick"),
                    data.getField("tickMax"), data.getField("loop"), data.getField("paused"), data.getField("active"),
                    data.getField("muted"), mediaPlayer, player.getMethod("isSafeUse"), player.getMethod("isValid"),
                    player.getMethod("isPaused"), player.getMethod("setPauseMode", boolean.class), player.getMethod("getTime"),
                    player.getMethod("seekTo", long.class), player.getMethod("setSpeed", float.class),
                    player.getMethod("isMuted"), player.getMethod("setMuteMode", boolean.class), player.getMethod("isLive"),
                    player.getMethod("isSeekAble"));
            JustReplay.LOGGER.info("WaterFrames detected: in-world videos follow the replay clock");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("waterframes.reflect", "WaterFrames support disabled, its classes changed", t);
        }
        return reflect;
    }

    /**
     * Display data was loaded or changed by a recorded packet (called by the WaterFrames mixins, on any side). During a
     * replay the display time is anchored to the recorded tick of that packet.
     */
    public static void onDataChanged(BlockEntity tile) {
        if (tile == null || tile.getLevel() == null || !tile.getLevel().isClientSide()) {
            return;
        }
        Playback playback = ReplayCompat.playback();
        Reflect r = reflect();
        if (playback == null || r == null) {
            return;
        }
        try {
            Object data = r.data.get(tile);
            int at = Playback.dispatchTick() >= 0 ? Playback.dispatchTick() : playback.cursor();
            Track track = TRACKS.computeIfAbsent(tile, k -> new Track());
            track.baseTick = r.tick.getInt(data);
            track.baseReplayTick = at;
            track.lastSeekCount = -1;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("waterframes.anchor", "Cannot read WaterFrames display data", t);
        }
    }

    /** Every client tick of a replay: display ticks follow the cursor, video players follow the display ticks. */
    static void tick(Minecraft mc, Playback playback) {
        Reflect r = reflect();
        if (r == null || TRACKS.isEmpty()) {
            return;
        }
        boolean exporting = VideoExporter.isActive();
        for (Map.Entry<BlockEntity, Track> entry : new ArrayList<>(TRACKS.entrySet())) {
            BlockEntity tile = entry.getKey();
            if (tile == null || tile.isRemoved() || tile.getLevel() != mc.level) {
                TRACKS.remove(tile);
                continue;
            }
            try {
                Object data = r.data.get(tile);
                r.tick.setInt(data, displayTick(r, data, entry.getValue(), playback.cursor()));
                syncPlayer(r, tile, data, entry.getValue(), playback, 0.0F, exporting);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("waterframes.sync", "Cannot sync a WaterFrames display with the replay", t);
            }
        }
    }

    /** Before an export frame is rendered: every video shows the exact time of the frame. */
    static void syncFrame(Playback playback, float partialTick) {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        List<Map.Entry<BlockEntity, Track>> entries = new ArrayList<>(TRACKS.entrySet());
        for (Map.Entry<BlockEntity, Track> entry : entries) {
            BlockEntity tile = entry.getKey();
            if (tile == null || tile.isRemoved()) {
                continue;
            }
            try {
                syncPlayer(r, tile, r.data.get(tile), entry.getValue(), playback, partialTick, true);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("waterframes.frame.sync", "Cannot move a WaterFrames video to the export frame", t);
            }
        }
    }

    /** The display tick at a replay tick, from the anchor: frozen while the display is paused or off. */
    private static int displayTick(Reflect r, Object data, Track track, int cursor) throws IllegalAccessException {
        if (r.paused.getBoolean(data) || !r.active.getBoolean(data)) {
            return track.baseTick;
        }
        long tick = (long) track.baseTick + Math.max(0, cursor - track.baseReplayTick);
        int max = r.tickMax.getInt(data);
        if (max > 0) {
            tick = r.loop.getBoolean(data) ? Math.floorMod(tick, (long) max) : Math.min(tick, max);
        }
        return (int) Math.min(Integer.MAX_VALUE, tick);
    }

    private static void syncPlayer(Reflect r, BlockEntity tile, Object data, Track track, Playback playback, float partialTick,
                                   boolean exporting) throws Exception {
        Object display = r.display.get(tile);
        Object player = display == null ? null : r.mediaPlayer.get(display);
        if (player == null) {
            return;
        }
        if (!(boolean) r.isSafeUse.invoke(player) || !(boolean) r.isValid.invoke(player)) {
            return;
        }
        boolean displayStopped = r.paused.getBoolean(data) || !r.active.getBoolean(data);
        // During an export the video never plays by itself: it is moved to the time of each frame.
        boolean pause = exporting || displayStopped || playback.isPaused();
        if ((boolean) r.isPaused.invoke(player) != pause) {
            r.setPauseMode.invoke(player, pause);
        }
        boolean mute = exporting || r.muted.getBoolean(data);
        if ((boolean) r.isMuted.invoke(player) != mute) {
            r.setMuteMode.invoke(player, mute);
        }
        if ((boolean) r.isLive.invoke(player) || !(boolean) r.isSeekAble.invoke(player)) {
            return;
        }
        float speed = playback.speed();
        if (!exporting && speed != track.lastSpeed) {
            r.setSpeed.invoke(player, speed);
            track.lastSpeed = speed;
        }
        long target = r.tick.getInt(data) * 50L + (displayStopped ? 0 : (long) (partialTick * 50.0F));
        if (exporting) {
            if (target != track.lastFrameTarget) {
                r.seekTo.invoke(player, target);
                track.lastFrameTarget = target;
            }
            return;
        }
        track.lastFrameTarget = Long.MIN_VALUE;
        long drift = Math.abs(target - (long) r.getTime.invoke(player));
        boolean seeked = playback.seekCount() != track.lastSeekCount;
        if (seeked || drift > MAX_DRIFT_MS * (long) Math.max(1.0F, speed)) {
            r.seekTo.invoke(player, target);
            track.lastSeekCount = playback.seekCount();
        }
    }

    /** State of the tracked displays, for diagnostics: position, display tick, video time and pause state. */
    public static String describe() {
        Reflect r = reflect();
        if (r == null) {
            return "n/a";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<BlockEntity, Track> entry : new ArrayList<>(TRACKS.entrySet())) {
            BlockEntity tile = entry.getKey();
            if (tile == null) {
                continue;
            }
            try {
                Object data = r.data.get(tile);
                Object display = r.display.get(tile);
                Object player = display == null ? null : r.mediaPlayer.get(display);
                out.append(tile.getBlockPos().toShortString()).append(" tick=").append(r.tick.getInt(data))
                        .append(" anchor=").append(entry.getValue().baseTick).append('@').append(entry.getValue().baseReplayTick);
                if (player != null) {
                    out.append(" video=").append(r.getTime.invoke(player)).append("ms paused=").append(r.isPaused.invoke(player))
                            .append(" muted=").append(r.isMuted.invoke(player));
                } else {
                    out.append(" video=none");
                }
                out.append("; ");
            } catch (Throwable t) {
                out.append("error ").append(t).append("; ");
            }
        }
        return out.isEmpty() ? "no display" : out.toString();
    }

    /** The replay ends: forget the displays (their level is unloaded with the replay world). */
    static void clear() {
        TRACKS.clear();
    }
}
