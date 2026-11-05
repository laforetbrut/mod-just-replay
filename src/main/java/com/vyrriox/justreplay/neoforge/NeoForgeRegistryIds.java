package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdMap;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry ids through NeoForge's snapshots. The file is a var int count, then for each registry its name and the bytes
 * of {@link RegistrySnapshot#STREAM_CODEC}: every replay recorded on NeoForge is in this format, do not change it.
 * Minecraft reverts the registries to their frozen state when the replay is left.
 *
 * @author vyrriox
 */
final class NeoForgeRegistryIds implements RegistryIdsBackend {
    @Override
    public byte[] encode() {
        Map<ResourceLocation, RegistrySnapshot> snapshot = RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(snapshot.size());
            snapshot.forEach((name, ids) -> {
                buf.writeResourceLocation(name);
                RegistrySnapshot.STREAM_CODEC.encode(buf, ids);
            });
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } catch (Exception e) {
            JustReplay.LOGGER.error("Cannot encode the registry ids", e);
            return null;
        } finally {
            buf.release();
        }
    }

    @Override
    public RegistryIdMap decode(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int count = buf.readVarInt();
            Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
            for (int i = 0; i < count; i++) {
                ResourceLocation name = buf.readResourceLocation();
                tables.put(name, table(RegistrySnapshot.STREAM_CODEC.decode(buf)));
            }
            return new RegistryIdMap(tables);
        } finally {
            buf.release();
        }
    }

    @Override
    public RegistryIdMap current() {
        Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
        RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT).forEach((name, snapshot) -> tables.put(name, table(snapshot)));
        return new RegistryIdMap(tables);
    }

    @Override
    public boolean apply(RegistryIdMap ids) {
        Map<ResourceLocation, RegistrySnapshot> snapshots = new HashMap<>();
        ids.tables().forEach((name, table) -> snapshots.put(name, snapshot(table)));
        Set<ResourceKey<?>> missing = RegistryManager.applySnapshot(snapshots, false);
        if (!missing.isEmpty()) {
            JustReplay.LOGGER.warn("{} registry entries of this replay are unknown here (mod missing?), e.g. {}",
                    missing.size(), missing.iterator().next());
        }
        return true;
    }

    private static RegistryIdMap.Table table(RegistrySnapshot snapshot) {
        return new RegistryIdMap.Table(snapshot.getIds(), snapshot.getAliases());
    }

    /** A snapshot has no public way to be built from its values, only its codec. */
    private static RegistrySnapshot snapshot(RegistryIdMap.Table table) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeMap(table.ids(), FriendlyByteBuf::writeVarInt, FriendlyByteBuf::writeResourceLocation);
            buf.writeMap(table.aliases(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeResourceLocation);
            return RegistrySnapshot.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
