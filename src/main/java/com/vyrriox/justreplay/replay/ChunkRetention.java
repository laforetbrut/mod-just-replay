package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.config.JRConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Keeps chunks loaded during playback instead of unloading them when the recording player walked away: recorded
 * forget packets are dropped, the client chunk storage is widened to the playback view distance, and chunks that
 * end up farther than that from the camera are evicted with a synthetic forget packet. Chunks inside the area the
 * recording server still had loaded are never evicted, since the stream keeps updating them.
 *
 * @author vyrriox
 */
final class ChunkRetention {
    private static final int EVICT_INTERVAL_TICKS = 20;
    private static final int MAX_EVICTIONS = 256;

    private final LongOpenHashSet loaded = new LongOpenHashSet();
    private ClientLevel level;
    private int recordedRadius = 2;
    private int centerX;
    private int centerZ;
    private int evictTimer;

    /** Radius given to the client chunk storage: the recorded server radius or the playback view distance. */
    int storageRadius() {
        return Math.max(recordedRadius, JRConfig.PLAYBACK_VIEW_DISTANCE.get());
    }

    /** Chunk radius to put in a recorded login packet; remembers the recorded one. */
    int onLogin(ClientboundLoginPacket login) {
        recordedRadius = login.chunkRadius();
        return storageRadius();
    }

    /** The packet to handle in place of a recorded one, or null to drop it. */
    Packet<? super ClientGamePacketListener> filter(Packet<? super ClientGamePacketListener> packet) {
        if (packet instanceof ClientboundForgetLevelChunkPacket) {
            return null;
        }
        if (packet instanceof ClientboundSetChunkCacheRadiusPacket radius) {
            recordedRadius = radius.getRadius();
            return new ClientboundSetChunkCacheRadiusPacket(storageRadius());
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket center) {
            centerX = center.getX();
            centerZ = center.getZ();
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            // Same range test as the client chunk storage, which would only log a warning for each refused chunk.
            int range = Math.max(2, storageRadius()) + 3;
            if (Math.abs(chunk.getX() - centerX) > range || Math.abs(chunk.getZ() - centerZ) > range) {
                return null;
            }
            loaded.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
        }
        return packet;
    }

    /** A new client level starts with an empty storage centered on chunk 0, 0. */
    void afterHandle(Minecraft mc) {
        if (mc.level != level) {
            level = mc.level;
            loaded.clear();
            centerX = 0;
            centerZ = 0;
        }
    }

    /** Evicts, once a second, the kept chunks that are too far from the camera. */
    void tick(Minecraft mc) {
        afterHandle(mc);
        if (++evictTimer < EVICT_INTERVAL_TICKS) {
            return;
        }
        evictTimer = 0;
        ClientPacketListener listener = mc.getConnection();
        Entity camera = mc.getCameraEntity();
        if (listener == null || level == null || camera == null || loaded.isEmpty()) {
            return;
        }
        int camX = SectionPos.blockToSectionCoord(camera.getX());
        int camZ = SectionPos.blockToSectionCoord(camera.getZ());
        int keep = storageRadius();
        int live = recordedRadius + 1;
        int evicted = 0;
        for (LongIterator it = loaded.iterator(); it.hasNext() && evicted < MAX_EVICTIONS; ) {
            long key = it.nextLong();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (Math.max(Math.abs(x - camX), Math.abs(z - camZ)) <= keep
                    || Math.abs(x - centerX) <= live && Math.abs(z - centerZ) <= live) {
                continue;
            }
            it.remove();
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z)).handle(listener);
                evicted++;
            }
        }
    }
}
