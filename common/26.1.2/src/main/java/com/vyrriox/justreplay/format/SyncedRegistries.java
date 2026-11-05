package com.vyrriox.justreplay.format;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Key order of the registries a server syncs from data packs (biomes, enchantments, dimension types...), saved next to
 * {@value RegistryIds#FILE}. Recorded packets name these entries by position and a replay decodes them against the
 * replay world's own registries, so a difference means wrong or unreadable content: it is detected and reported, not
 * repaired. Older recordings have no such file and are not checked; older versions of the mod ignore it.
 *
 * @author vyrriox
 */
public final class SyncedRegistries {
    public static final String FILE = "synced_registries.json";
    private static final Gson GSON = new Gson();

    private SyncedRegistries() {
    }

    /** The keys of the synced registries of a connection, in id order, as JSON. */
    public static String encode(RegistryAccess access) {
        return GSON.toJson(keys(access));
    }

    /** Writes keys taken with {@link #encode}; a failure is logged, the recording goes on without them. */
    public static void write(Path dir, String json) {
        if (json == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(FILE), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot save the synced registry keys for {}: {}", dir, e.toString());
        }
    }

    /** Names of the recorded registries that differ here, logged with their first difference; empty when none or no file. */
    public static List<String> differing(Path dir, RegistryAccess access) {
        List<String> result = new ArrayList<>();
        Path file = dir.resolve(FILE);
        try {
            if (!Files.exists(file)) {
                return result;
            }
            Map<String, List<String>> recorded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, List<String>>>() {
                    }.getType());
            if (recorded == null) {
                return result;
            }
            Map<String, List<String>> host = keys(access);
            for (Map.Entry<String, List<String>> entry : recorded.entrySet()) {
                List<String> here = host.get(entry.getKey());
                if (!entry.getValue().equals(here)) {
                    result.add(entry.getKey());
                    JustReplay.LOGGER.warn("Registry {} differs from the recording ({}), its content may decode wrongly", entry.getKey(),
                            describe(entry.getValue(), here));
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot compare the synced registries of {}: {}", dir, e.toString());
        }
        return result;
    }

    private static String describe(List<String> recorded, List<String> here) {
        if (here == null) {
            return "absent here";
        }
        int common = Math.min(recorded.size(), here.size());
        for (int i = 0; i < common; i++) {
            if (!recorded.get(i).equals(here.get(i))) {
                return "id " + i + " is " + recorded.get(i) + " in the recording, " + here.get(i) + " here";
            }
        }
        return recorded.size() + " entries in the recording, " + here.size() + " here";
    }

    private static Map<String, List<String>> keys(RegistryAccess access) {
        Map<String, List<String>> result = new TreeMap<>();
        access.registries().forEach(entry -> {
            // Static registries are covered by RegistryIds.
            if (!BuiltInRegistries.REGISTRY.containsKey(entry.key().identifier())) {
                result.put(entry.key().identifier().toString(), keys(entry.value()));
            }
        });
        return result;
    }

    private static <T> List<String> keys(Registry<T> registry) {
        List<String> keys = new ArrayList<>(registry.size());
        for (int id = 0; id < registry.size(); id++) {
            Identifier key = registry.getKey(registry.byId(id));
            keys.add(String.valueOf(key));
        }
        return keys;
    }
}
