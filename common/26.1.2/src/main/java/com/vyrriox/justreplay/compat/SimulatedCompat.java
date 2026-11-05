package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Simulated (bundled in Create Aeronautics) ropes between Rope Connectors and Rope Winches.
 * <p>
 * The server sends a rope to a client only when the client starts tracking its owner block (a rope data payload with
 * the segment points, then one per change, and a stopped payload once it settles). The client keeps the rope on the
 * owner block entity's rope behaviour, not in the chunk data. A rope that existed before a recording started, or
 * before a checkpoint, was therefore missing from the snapshot. Snapshots and checkpoints now rebuild those payloads
 * from the client state: one rope data payload per buffered point snapshot, then a stopped payload for settled ropes.
 * They are written after the world chunks and the Sable sub-levels, so the owner block entities exist.
 * Everything goes through reflection: no Simulated class is loaded when Simulated is absent.
 *
 * @author vyrriox
 */
public final class SimulatedCompat {
    private static final double MATCH_SQR = 1.0E-6;

    private static Reflect reflect;
    private static boolean reflectFailed;

    private SimulatedCompat() {
    }

    private record Reflect(Method getBehaviour, Object behaviourType, Method getClientStrand, Method getAttachmentPoint,
                           Method getUuid, Method getPoints, Field startAttachment, Field endAttachment, Field stopped,
                           Method pointPosition, Method pointSnapshots, Method snapshotTick, Method snapshotPosition,
                           Constructor<?> dataPacket, Constructor<?> stoppedPacket) {
    }

    private static synchronized Reflect reflect() {
        if (reflect != null || reflectFailed) {
            return reflect;
        }
        try {
            ClassLoader loader = SimulatedCompat.class.getClassLoader();
            String base = "dev.simulated_team.simulated.";
            Class<?> smart = Class.forName("com.simibubi.create.foundation.blockEntity.SmartBlockEntity", false, loader);
            Class<?> behaviourType = Class.forName("com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType", false, loader);
            Class<?> holder = Class.forName(base + "content.blocks.rope.RopeStrandHolderBehavior", false, loader);
            Class<?> strand = Class.forName(base + "content.blocks.rope.strand.client.ClientRopeStrand", false, loader);
            Class<?> point = Class.forName(base + "content.blocks.rope.strand.client.ClientRopePoint", false, loader);
            Class<?> snapshot = Class.forName(base + "content.blocks.rope.strand.client.ClientRopePoint$Snapshot", false, loader);
            Class<?> data = Class.forName(base + "network.packets.rope.ClientboundRopeDataPacket", false, loader);
            Class<?> stop = Class.forName(base + "network.packets.rope.ClientboundRopeStoppedPacket", false, loader);
            Field stopped = strand.getDeclaredField("stopped");
            stopped.setAccessible(true);
            reflect = new Reflect(smart.getMethod("getBehaviour", behaviourType), holder.getField("TYPE").get(null),
                    holder.getMethod("getClientStrand"), holder.getMethod("getAttachmentPoint"),
                    strand.getMethod("getUuid"), strand.getMethod("getPoints"), strand.getField("startAttachment"),
                    strand.getField("endAttachment"), stopped, point.getMethod("position"), point.getMethod("snapshots"),
                    snapshot.getMethod("interpolationTick"), snapshot.getMethod("position"),
                    data.getConstructor(int.class, BlockPos.class, UUID.class, List.class, BlockPos.class, BlockPos.class),
                    stop.getConstructor(BlockPos.class));
            JustReplay.LOGGER.info("Simulated detected: ropes are written into snapshots");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("simulated.reflect", "Simulated rope support disabled, its classes changed", t);
        }
        return reflect;
    }

    /** A rope holder block entity: its position, its rope behaviour and its attachment point. */
    private record Holder(BlockPos pos, Object behaviour, Vec3 attachment) {
    }

    /** Writes every rope the client knows as the payloads the server sends on tracking start. Client thread. */
    static void writeSnapshot(Consumer<Packet<?>> out) throws ReflectiveOperationException {
        Reflect r = reflect();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (r == null || level == null || mc.player == null) {
            return;
        }
        List<LevelChunk> chunks = new ArrayList<>();
        int radius = mc.options.renderDistance().get() + 2;
        int cx = mc.player.chunkPosition().x();
        int cz = mc.player.chunkPosition().z();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }
        if (ReplayCompat.SABLE) {
            chunks.addAll(SableCompat.plotChunks());
        }
        List<Holder> holders = new ArrayList<>();
        for (LevelChunk chunk : chunks) {
            for (BlockEntity blockEntity : List.copyOf(chunk.getBlockEntities().values())) {
                if (!r.getBehaviour.getDeclaringClass().isInstance(blockEntity)) {
                    continue;
                }
                Object behaviour = r.getBehaviour.invoke(blockEntity, r.behaviourType);
                if (behaviour != null) {
                    Vec3 attachment = null;
                    try {
                        attachment = (Vec3) r.getAttachmentPoint.invoke(behaviour);
                    } catch (Throwable ignored) {
                        // No attachment point: the block can still own a rope, it just cannot be matched as an end.
                    }
                    holders.add(new Holder(blockEntity.getBlockPos(), behaviour, attachment));
                }
            }
        }
        for (Holder owner : holders) {
            Object strand = r.getClientStrand.invoke(owner.behaviour);
            if (strand == null) {
                continue;
            }
            try {
                writeStrand(r, owner, strand, holders, out);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("simulated.snapshot.rope", "Skipping a Simulated rope in the snapshot", t);
            }
        }
    }

    private static void writeStrand(Reflect r, Holder owner, Object strand, List<Holder> holders, Consumer<Packet<?>> out)
            throws ReflectiveOperationException {
        UUID uuid = (UUID) r.getUuid.invoke(strand);
        BlockPos start = endPos((Vec3) r.startAttachment.get(strand), holders);
        BlockPos end = endPos((Vec3) r.endAttachment.get(strand), holders);
        List<?> points = List.copyOf((List<?>) r.getPoints.invoke(strand));
        // Every tick some point still has a snapshot for, oldest first, so the interpolation resumes as it was.
        TreeSet<Double> ticks = new TreeSet<>();
        for (Object point : points) {
            for (Object snapshot : (List<?>) r.pointSnapshots.invoke(point)) {
                ticks.add((Double) r.snapshotTick.invoke(snapshot));
            }
        }
        if (ticks.isEmpty()) {
            // Nothing buffered: the current positions, with an old tick so the client keeps them as they are.
            List<Vector3d> positions = new ArrayList<>();
            for (Object point : points) {
                positions.add(new Vector3d((Vector3d) r.pointPosition.invoke(point)));
            }
            out.accept(dataPacket(r, 0, owner.pos, uuid, positions, start, end));
        } else {
            for (double tick : ticks) {
                List<Vector3d> positions = new ArrayList<>();
                for (Object point : points) {
                    positions.add(positionAt(r, point, tick));
                }
                out.accept(dataPacket(r, (int) tick, owner.pos, uuid, positions, start, end));
            }
        }
        if ((Boolean) r.stopped.get(strand)) {
            out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.stoppedPacket.newInstance(owner.pos)));
        }
    }

    /** The point position at a buffered tick: its snapshot at or before that tick, else its current position. */
    private static Vector3d positionAt(Reflect r, Object point, double tick) throws ReflectiveOperationException {
        Vector3d found = null;
        for (Object snapshot : (List<?>) r.pointSnapshots.invoke(point)) {
            if ((Double) r.snapshotTick.invoke(snapshot) <= tick) {
                found = (Vector3d) r.snapshotPosition.invoke(snapshot);
            }
        }
        return new Vector3d(found != null ? found : (Vector3d) r.pointPosition.invoke(point));
    }

    /** The block whose attachment point is this rope end, or null. */
    private static BlockPos endPos(Vec3 attachment, List<Holder> holders) {
        if (attachment == null) {
            return null;
        }
        for (Holder holder : holders) {
            if (holder.attachment != null && holder.attachment.distanceToSqr(attachment) < MATCH_SQR) {
                return holder.pos;
            }
        }
        return null;
    }

    private static Packet<?> dataPacket(Reflect r, int tick, BlockPos owner, UUID uuid, List<Vector3d> points,
                                        BlockPos start, BlockPos end) throws ReflectiveOperationException {
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.dataPacket.newInstance(tick, owner, uuid, points,
                start, end));
    }
}
