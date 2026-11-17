package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.ClientPlatform;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Numeric ids (blocks, items, entity types...) depend on the world or server a replay was recorded on. They are saved
 * with the replay and applied before playback so recorded packets decode to the right content. The loader's part is in
 * {@link RegistryIdsBackend}.
 *
 * @author vyrriox
 */
public final class RegistryIds {
    public static final String FILE = "registries.bin";
    /** Namespace first, then path: the order the loader used before, so a recording keeps its numbering. */
    private static final java.util.Comparator<ResourceLocation> NAMESPACE_FIRST = java.util.Comparator
            .comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath);

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
        return ClientPlatform.get().registryIds().encode();
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
    public static RegistryIdMap complete(RegistryIdMap recorded) {
        Map<ResourceLocation, RegistryIdMap.Table> result = new HashMap<>();
        int added = 0;
        String example = null;
        for (Map.Entry<ResourceLocation, RegistryIdMap.Table> entry : recorded.tables().entrySet()) {
            RegistryIdMap.Table table = entry.getValue();
            Registry<?> registry = BuiltInRegistries.REGISTRY.get(entry.getKey());
            if (registry == null) {
                result.put(entry.getKey(), table);
                continue;
            }
            Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(table.ids());
            Set<ResourceLocation> listed = new HashSet<>(ids.values());
            List<ResourceLocation> extra = new ArrayList<>();
            for (ResourceLocation key : registry.keySet()) {
                if (!listed.contains(key)) {
                    extra.add(key);
                }
            }
            if (extra.isEmpty()) {
                result.put(entry.getKey(), table);
                continue;
            }
            extra.sort(NAMESPACE_FIRST);
            int next = ids.isEmpty() ? 0 : ids.lastIntKey() + 1;
            for (ResourceLocation key : extra) {
                ids.put(next++, key);
            }
            added += extra.size();
            if (example == null) {
                example = entry.getKey() + " / " + extra.get(0);
            }
            result.put(entry.getKey(), new RegistryIdMap.Table(ids, table.aliases()));
        }
        if (added > 0) {
            JustReplay.LOGGER.info("{} registry entries are not in this replay's ids and keep a new one (e.g. {})", added, example);
        }
        return new RegistryIdMap(result);
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
        try {
            RegistryIdsBackend backend = ClientPlatform.get().registryIds();
            return backend.apply(complete(backend.decode(Files.readAllBytes(file))));
        } catch (Exception e) {
            JustReplay.LOGGER.error("Cannot apply the registry ids of {}", dir, e);
            return false;
        }
    }
}
