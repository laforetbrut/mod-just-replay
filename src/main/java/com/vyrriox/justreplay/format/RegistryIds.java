package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NeoForge numeric ids (blocks, items, entity types...) depend on the world or server a replay was recorded on.
 * They are saved with the replay and applied before playback so recorded packets decode to the right content.
 * Minecraft reverts the registries to their frozen state when the replay is left.
 *
 * @author vyrriox
 */
public final class RegistryIds {
    public static final String FILE = "registries.bin";

    private RegistryIds() {
    }

    /** Saves the ids currently in use by the client, which are the server's ids once connected. */
    public static void save(Path dir) {
        byte[] bytes = encode();
        if (bytes != null) {
            write(dir, bytes);
        }
    }

    /** The ids currently in use by the client, encoded as in {@value #FILE}; null when they cannot be read. */
    public static byte[] encode() {
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

    /** Writes ids taken earlier with {@link #encode()} into a replay folder. */
    public static void write(Path dir, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(FILE), bytes);
        } catch (Exception e) {
            JustReplay.LOGGER.error("Cannot save registry ids for {}", dir, e);
        }
    }

    /**
     * The recorded ids plus every local entry the recording does not list, appended after the highest recorded id.
     * NeoForge clears a registry's ids before applying a snapshot and gives one back only to the listed keys: an entry
     * missing from the recording (a mod registering it later, a pack updated since) would be left without an id. In
     * singleplayer the replay's own server shares these registries and then fails to encode packets that name such an
     * entry (the recipes sent at login, whose serializer had no id), which closed the replay at once. Recorded packets
     * never name the appended entries, so their ids do not matter, only that they have one.
     */
    public static Map<ResourceLocation, RegistrySnapshot> complete(Map<ResourceLocation, RegistrySnapshot> recorded) {
        Map<ResourceLocation, RegistrySnapshot> result = new HashMap<>();
        int added = 0;
        String example = null;
        for (Map.Entry<ResourceLocation, RegistrySnapshot> entry : recorded.entrySet()) {
            RegistrySnapshot snapshot = entry.getValue();
            Registry<?> registry = BuiltInRegistries.REGISTRY.get(entry.getKey());
            if (registry == null) {
                result.put(entry.getKey(), snapshot);
                continue;
            }
            Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(snapshot.getIds());
            Set<ResourceLocation> listed = new HashSet<>(ids.values());
            List<ResourceLocation> extra = new ArrayList<>();
            for (ResourceLocation key : registry.keySet()) {
                if (!listed.contains(key)) {
                    extra.add(key);
                }
            }
            if (extra.isEmpty()) {
                result.put(entry.getKey(), snapshot);
                continue;
            }
            extra.sort(ResourceLocation::compareNamespaced);
            int next = ids.isEmpty() ? 0 : ids.lastIntKey() + 1;
            for (ResourceLocation key : extra) {
                ids.put(next++, key);
            }
            added += extra.size();
            if (example == null) {
                example = entry.getKey() + " / " + extra.get(0);
            }
            result.put(entry.getKey(), rebuild(ids, snapshot.getAliases()));
        }
        if (added > 0) {
            JustReplay.LOGGER.info("{} registry entries are not in this replay's ids and keep a new one (e.g. {})", added, example);
        }
        return result;
    }

    public static RegistrySnapshot rebuild(Int2ObjectSortedMap<ResourceLocation> ids, Map<ResourceLocation, ResourceLocation> aliases) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeMap(ids, FriendlyByteBuf::writeVarInt, FriendlyByteBuf::writeResourceLocation);
            buf.writeMap(aliases, FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeResourceLocation);
            return RegistrySnapshot.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * Applies the ids saved with a replay. Returns false when the replay has none (older recordings). The registries
     * are incomplete while this runs: nothing may be encoding or decoding on another thread, see ReplaySession.
     */
    public static boolean apply(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            return false;
        }
        FriendlyByteBuf buf = null;
        try {
            buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(file)));
            int count = buf.readVarInt();
            Map<ResourceLocation, RegistrySnapshot> snapshot = new HashMap<>();
            for (int i = 0; i < count; i++) {
                ResourceLocation name = buf.readResourceLocation();
                snapshot.put(name, RegistrySnapshot.STREAM_CODEC.decode(buf));
            }
            Set<ResourceKey<?>> missing = RegistryManager.applySnapshot(complete(snapshot), false);
            if (!missing.isEmpty()) {
                JustReplay.LOGGER.warn("{} registry entries of this replay are unknown here (mod missing?), e.g. {}",
                        missing.size(), missing.iterator().next());
            }
            return true;
        } catch (Exception e) {
            JustReplay.LOGGER.error("Cannot apply the registry ids of {}", dir, e);
            return false;
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }
}
