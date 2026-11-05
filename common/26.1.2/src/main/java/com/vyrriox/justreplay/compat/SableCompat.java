package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.joml.Vector2i;
import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sable (physics sub-levels: moving block structures) and the mods built on it, Create Aeronautics and Simulated.
 * <ul>
 *     <li>A sub-level lives in a plot of chunks far from the world (about 20 million blocks away) and is drawn at its
 *     pose. Start and stop of tracking, the plot chunks, bounds and names come through the connection as mod payloads
 *     and normal chunk packets, so the recorder already sees them.</li>
 *     <li>The poses themselves (snapshot and snapshot info payloads) usually skip the connection: in singleplayer the
 *     integrated server hands them to the client directly, in multiplayer they come over Sable's own UDP channel. The
 *     compat mixins catch both paths and record them as the same payloads Sable sends over TCP when UDP is off, which
 *     its client handles exactly like the UDP ones. They are played back at their recorded tick and Sable's client
 *     interpolation smooths them as it does live.</li>
 *     <li>Snapshots and checkpoints write every sub-level the client knows: tracking start, plot chunks, finalize, the
 *     recent poses and the interpolation clock, so a recording started mid-flight and seeks show the ships.</li>
 *     <li>During playback the plot chunks are kept whatever their distance to the camera, the replay world's own
 *     server cannot hand poses to the client, and the payloads that only make sense for the live player (UDP
 *     activation, player freeze, gizmo mode) are dropped. The pose timing follows the playback speed.</li>
 * </ul>
 * Everything goes through reflection: no Sable class is loaded when Sable is absent.
 *
 * @author vyrriox
 */
public final class SableCompat {
    private static final String NS = "sable";
    private static final Identifier SNAPSHOT = id("snapshot_packet");
    private static final Identifier SNAPSHOT_INFO = id("snapshot_info_packet");
    private static final Identifier START_TRACKING = id("start_tracking_sub_level");
    /** Payloads that act on the live local player or the live connection, never on the replayed world. */
    private static final Set<Identifier> LIVE_ONLY = Set.of(id("udp_activation"), id("freeze_player"),
            id("enter_gizmo_mode"));
    /** Server send interval assumed when a snapshot rebuilds the interpolation clock. */
    private static final int TICK_MS = 50;

    private static Reflect reflect;
    private static boolean reflectFailed;
    private static volatile Method addressedPacket;

    private SableCompat() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NS, path);
    }

    private record Reflect(Method getContainer, Method inBounds, Method getSubLevel, Method getAllSubLevels, Method getOrigin,
                           Method getInterpolation, Field mostRecentTick, Method isStopped, Method isRemoved, Method getPlot,
                           Field plotPos, Method getLoadedChunks, Method holderChunk, Method getLightEngine,
                           Method getBoundingBox, Method getUniqueId, Method getName, Method lastPose, Method logicalPose,
                           Method getInterpolator, Field buffer, Field interpolatorStopped, Method snapshotTick,
                           Method snapshotPose, Constructor<?> pose, Constructor<?> startTracking, Method startPlot,
                           Constructor<?> finalizeSubLevel, Constructor<?> stopMoving, Constructor<?> snapshot,
                           Constructor<?> entry, Constructor<?> info, Field infoMs, Field infoTick, Field infoStopped) {
    }

    /** Resolves Sable's classes once; logs the detection line. */
    static synchronized Reflect reflect() {
        if (reflect != null || reflectFailed) {
            return reflect;
        }
        if (!ReplayCompat.SABLE) {
            reflectFailed = true;
            return null;
        }
        try {
            ClassLoader loader = SableCompat.class.getClassLoader();
            Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer", false, loader);
            Class<?> clientContainer = Class.forName("dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer", false, loader);
            Class<?> state = Class.forName("dev.ryanhcode.sable.network.client.ClientSableInterpolationState", false, loader);
            Class<?> subLevel = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel", false, loader);
            Class<?> clientSubLevel = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel", false, loader);
            Class<?> plot = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot", false, loader);
            Class<?> holder = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder", false, loader);
            Class<?> interpolator = Class.forName("dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator", false, loader);
            Class<?> snapshotRecord = Class.forName("dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator$Snapshot", false, loader);
            Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d", false, loader);
            Class<?> poseView = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc", false, loader);
            Class<?> bounds = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic", false, loader);
            Class<?> start = Class.forName("dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket", false, loader);
            Class<?> finalize = Class.forName("dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket", false, loader);
            Class<?> stop = Class.forName("dev.ryanhcode.sable.network.packets.tcp.ClientboundStopMovingSubLevelPacket", false, loader);
            Class<?> snapshot = Class.forName("dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket", false, loader);
            Class<?> entry = Class.forName("dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket$Entry", false, loader);
            Class<?> info = Class.forName("dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket", false, loader);
            Class<?> vector = Class.forName("org.joml.Vector3fc", false, loader);
            reflect = new Reflect(container.getMethod("getContainer", Level.class),
                    container.getMethod("inBounds", int.class, int.class),
                    container.getMethod("getSubLevel", int.class, int.class),
                    container.getMethod("getAllSubLevels"),
                    container.getMethod("getOrigin"),
                    clientContainer.getMethod("getInterpolation"),
                    accessible(state.getDeclaredField("mostRecentTick")),
                    state.getMethod("isStopped"),
                    subLevel.getMethod("isRemoved"),
                    subLevel.getMethod("getPlot"),
                    plot.getField("plotPos"),
                    plot.getMethod("getLoadedChunks"),
                    holder.getMethod("getChunk"),
                    plot.getMethod("getLightEngine"),
                    plot.getMethod("getBoundingBox"),
                    subLevel.getMethod("getUniqueId"),
                    subLevel.getMethod("getName"),
                    subLevel.getMethod("lastPose"),
                    subLevel.getMethod("logicalPose"),
                    clientSubLevel.getMethod("getInterpolator"),
                    interpolator.getField("buffer"),
                    accessible(interpolator.getDeclaredField("stopped")),
                    snapshotRecord.getMethod("gameTick"),
                    snapshotRecord.getMethod("pose"),
                    pose.getConstructor(poseView),
                    start.getConstructor(long.class, UUID.class, poseView, pose, bounds, String.class, int.class),
                    start.getMethod("plotCoordinate"),
                    finalize.getConstructor(long.class),
                    stop.getConstructor(long.class),
                    snapshot.getConstructor(int.class, List.class),
                    entry.getConstructor(long.class, pose, vector, vector),
                    info.getConstructor(int.class, int.class, boolean.class),
                    accessible(info.getDeclaredField("msSinceLast")),
                    accessible(info.getDeclaredField("gameTick")),
                    accessible(info.getDeclaredField("stopped")));
            JustReplay.LOGGER.info("Sable detected: sub-level poses are recorded from every network path and sub-levels are"
                    + " written into snapshots{}", ReplayCompat.AERONAUTICS ? " (Create Aeronautics present)" : "");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("sable.reflect", "Sable support disabled, its classes changed", t);
        }
        return reflect;
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    // ---------------------------------------------------------------- recording

    /**
     * A pose payload Sable's server hands to the local client without the connection (singleplayer owner). Called
     * on the server thread. Returns true when the packet must not reach the client: the replay world's own server
     * has no business moving the replayed sub-levels.
     */
    public static boolean onLocalSend(MinecraftServer server, Object packet) {
        if (ReplaySession.isHostServer(server) || ReplaySession.current() != null) {
            return true;
        }
        recordPose(packet);
        return false;
    }

    /** A packet that came over Sable's UDP channel (an {@code AddressedSableUDPPacket}). Netty thread. */
    public static void onUdpReceived(Object addressed) {
        if (addressed == null || ReplaySession.current() != null) {
            return;
        }
        try {
            Method method = addressedPacket;
            if (method == null) {
                method = addressed.getClass().getMethod("packet");
                addressedPacket = method;
            }
            recordPose(method.invoke(addressed));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("sable.udp", "Cannot record a Sable sub-level pose received over UDP", t);
        }
    }

    private static void recordPose(Object packet) {
        if (packet instanceof CustomPacketPayload payload) {
            Identifier type = payload.type().id();
            if (SNAPSHOT.equals(type) || SNAPSHOT_INFO.equals(type)) {
                Recorder.recordSideChannel(new ClientboundCustomPayloadPacket(payload));
            }
        }
    }

    /**
     * Writes every sub-level of the client level as a tracking start, its plot chunks and a finalize, then the recent
     * poses of each and the interpolation clock. Client thread.
     */
    static void writeSnapshot(Consumer<Packet<?>> out) throws ReflectiveOperationException {
        Reflect r = reflect();
        ClientLevel level = Minecraft.getInstance().level;
        if (r == null || level == null) {
            return;
        }
        Object container = r.getContainer.invoke(null, level);
        if (container == null) {
            return;
        }
        Object state = r.getInterpolation.invoke(container);
        double mostRecent = r.mostRecentTick.getDouble(state);
        int clock = mostRecent < 0 ? 0 : (int) mostRecent;
        boolean stopped = (Boolean) r.isStopped.invoke(state);
        Vector2i origin = (Vector2i) r.getOrigin.invoke(container);
        // Poses by tick, all sub-levels together, as the server batches them.
        TreeMap<Integer, List<Object>> poses = new TreeMap<>();
        List<Long> halted = new ArrayList<>();
        int written = 0;
        for (Object subLevel : List.copyOf((List<?>) r.getAllSubLevels.invoke(container))) {
            if ((Boolean) r.isRemoved.invoke(subLevel)) {
                continue;
            }
            try {
                Object plot = r.getPlot.invoke(subLevel);
                ChunkPos plotPos = (ChunkPos) r.plotPos.get(plot);
                long key = ChunkPos.pack(plotPos.x() - origin.x, plotPos.z() - origin.y);
                Object interpolator = r.getInterpolator.invoke(subLevel);
                List<Object[]> history = new ArrayList<>();
                List<?> buffer = (List<?>) r.buffer.get(interpolator);
                synchronized (buffer) {
                    for (Object snapshot : buffer) {
                        history.add(new Object[]{r.snapshotTick.invoke(snapshot), r.snapshotPose.invoke(snapshot)});
                    }
                }
                int startTick;
                Object last;
                Object current;
                if (history.isEmpty()) {
                    startTick = clock;
                    last = r.pose.newInstance(r.lastPose.invoke(subLevel));
                    current = r.pose.newInstance(r.logicalPose.invoke(subLevel));
                } else {
                    // The oldest pose starts the track, the newer ones follow in tick order.
                    startTick = (Integer) history.get(0)[0];
                    last = r.pose.newInstance(history.get(0)[1]);
                    current = r.pose.newInstance(history.get(0)[1]);
                }
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.startTracking.newInstance(key,
                        r.getUniqueId.invoke(subLevel), last, current, r.getBoundingBox.invoke(plot), r.getName.invoke(subLevel),
                        startTick)));
                LevelLightEngine light = (LevelLightEngine) r.getLightEngine.invoke(plot);
                for (Object holder : List.copyOf((Collection<?>) r.getLoadedChunks.invoke(plot))) {
                    LevelChunk chunk = (LevelChunk) r.holderChunk.invoke(holder);
                    if (chunk != null) {
                        out.accept(chunkPacket(chunk, light, level));
                    }
                }
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.finalizeSubLevel.newInstance(key)));
                for (int i = 1; i < history.size(); i++) {
                    Object entry = r.entry.newInstance(key, r.pose.newInstance(history.get(i)[1]), new Vector3f(), new Vector3f());
                    poses.computeIfAbsent((Integer) history.get(i)[0], t -> new ArrayList<>()).add(entry);
                }
                if ((Boolean) r.interpolatorStopped.get(interpolator)) {
                    halted.add(key);
                }
                written++;
            } catch (Throwable t) {
                ReplayCompat.warnOnce("sable.snapshot.sublevel", "Skipping a Sable sub-level in the snapshot", t);
            }
        }
        if (written == 0) {
            return;
        }
        for (Map.Entry<Integer, List<Object>> poseTick : poses.entrySet()) {
            out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.snapshot.newInstance(poseTick.getKey(),
                    poseTick.getValue())));
        }
        for (long key : halted) {
            out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.stopMoving.newInstance(key)));
        }
        out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.info.newInstance(TICK_MS, clock, stopped)));
    }

    /** The loaded plot chunks of every sub-level of the client level (empty when Sable is unusable). Client thread. */
    static List<LevelChunk> plotChunks() throws ReflectiveOperationException {
        List<LevelChunk> chunks = new ArrayList<>();
        Reflect r = reflect();
        ClientLevel level = Minecraft.getInstance().level;
        Object container = r == null || level == null ? null : r.getContainer.invoke(null, level);
        if (container == null) {
            return chunks;
        }
        for (Object subLevel : List.copyOf((List<?>) r.getAllSubLevels.invoke(container))) {
            if ((Boolean) r.isRemoved.invoke(subLevel)) {
                continue;
            }
            for (Object holder : List.copyOf((Collection<?>) r.getLoadedChunks.invoke(r.getPlot.invoke(subLevel)))) {
                LevelChunk chunk = (LevelChunk) r.holderChunk.invoke(holder);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }
        return chunks;
    }

    private static ClientboundLevelChunkWithLightPacket chunkPacket(LevelChunk chunk, LevelLightEngine plotLight, ClientLevel level) {
        try {
            return new ClientboundLevelChunkWithLightPacket(chunk, plotLight, null, null);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("sable.snapshot.light", "Sable plot light unreadable, using the level light for sub-level chunks", t);
            return new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        }
    }

    // ---------------------------------------------------------------- playback

    /** True when a chunk lies in Sable's plot area of the current client level (a sub-level chunk). */
    public static boolean isPlotChunk(int x, int z) {
        Reflect r = reflect();
        ClientLevel level = Minecraft.getInstance().level;
        if (r == null || level == null) {
            return false;
        }
        try {
            Object container = r.getContainer.invoke(null, level);
            return container != null && (Boolean) r.inBounds.invoke(container, x, z);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("sable.plot", "Cannot tell Sable plot chunks apart", t);
            return false;
        }
    }

    /**
     * A recorded Sable payload about to be handled: the packet to handle in its place, or null to drop it. Live-only
     * payloads are dropped, a tracking start for a plot already in use is dropped (Sable would throw), and the pose
     * timing is scaled to the playback speed so the interpolation advances one recorded tick per recorded tick.
     */
    static Packet<?> filterPlayback(Packet<?> packet, Playback playback) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) {
            return packet;
        }
        Identifier type = custom.payload().type().id();
        if (!NS.equals(type.getNamespace())) {
            return packet;
        }
        if (LIVE_ONLY.contains(type)) {
            return null;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        try {
            if (START_TRACKING.equals(type)) {
                long key = (Long) r.startPlot.invoke(custom.payload());
                ClientLevel level = Minecraft.getInstance().level;
                Object container = level == null ? null : r.getContainer.invoke(null, level);
                if (container != null && r.getSubLevel.invoke(container, ChunkPos.getX(key), ChunkPos.getZ(key)) != null) {
                    return null;
                }
            } else if (SNAPSHOT_INFO.equals(type) && playback != null) {
                float speed = playback.speed();
                if (speed > 0 && speed != 1.0F) {
                    Object info = custom.payload();
                    int ms = Math.max(1, Math.round(r.infoMs.getInt(info) / speed));
                    return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.info.newInstance(ms,
                            r.infoTick.getInt(info), r.infoStopped.getBoolean(info)));
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("sable.playback", "Cannot adjust a recorded Sable packet", t);
        }
        return packet;
    }
}
