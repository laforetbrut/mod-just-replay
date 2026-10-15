package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

    /** Applies the ids saved with a replay. Returns false when the replay has none (older recordings). */
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
            Set<ResourceKey<?>> missing = RegistryManager.applySnapshot(snapshot, false);
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
