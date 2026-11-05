package com.vyrriox.justreplay.fabric;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdMap;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry ids on Fabric. The file is a format byte, a var int count, then for each registry its name and its ids.
 * Applying recorded ids is not supported yet: a replay runs with the ids of this game.
 *
 * @author vyrriox
 */
final class FabricRegistryIds implements RegistryIdsBackend {
    private static final int FORMAT = 1;

    @Override
    public byte[] encode() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            Map<ResourceLocation, RegistryIdMap.Table> tables = current().tables();
            buf.writeByte(FORMAT);
            buf.writeVarInt(tables.size());
            tables.forEach((name, table) -> {
                buf.writeResourceLocation(name);
                buf.writeMap(table.ids(), FriendlyByteBuf::writeVarInt, FriendlyByteBuf::writeResourceLocation);
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
            int format = buf.readByte();
            if (format != FORMAT) {
                throw new IllegalArgumentException("Registry ids not written on Fabric, format " + format);
            }
            int count = buf.readVarInt();
            Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
            for (int i = 0; i < count; i++) {
                ResourceLocation name = buf.readResourceLocation();
                Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(
                        buf.readMap(FriendlyByteBuf::readVarInt, FriendlyByteBuf::readResourceLocation));
                tables.put(name, new RegistryIdMap.Table(ids, Map.of()));
            }
            return new RegistryIdMap(tables);
        } finally {
            buf.release();
        }
    }

    @Override
    public RegistryIdMap current() {
        Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            tables.put(registry.key().location(), new RegistryIdMap.Table(ids(registry), Map.of()));
        }
        return new RegistryIdMap(tables);
    }

    private static <T> Int2ObjectSortedMap<ResourceLocation> ids(Registry<T> registry) {
        Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>();
        for (T value : registry) {
            ids.put(registry.getId(value), registry.getKey(value));
        }
        return ids;
    }

    @Override
    public boolean apply(RegistryIdMap ids) {
        return false;
    }
}
