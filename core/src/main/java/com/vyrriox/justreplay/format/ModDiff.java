package com.vyrriox.justreplay.format;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Differences between the mods a replay was recorded with ({@code meta.json}, entries {@code modid@version}) and the
 * mods installed now. Mods that change nothing in a replay are left out: Just Replay itself, the game, Java, the
 * loader and the libraries it bundles, and the modules of Fabric API (Fabric API itself still counts). Plain Java,
 * no game code.
 *
 * @author vyrriox
 */
public final class ModDiff {
    /** A mod and its version (empty when the entry had none). */
    public record Mod(String id, String version) {
    }

    /** A mod installed in another version than the one recorded. */
    public record Changed(String id, String recorded, String installed) {
    }

    private static final ModDiff NONE = new ModDiff(List.of(), List.of(), List.of());

    private final List<Mod> missing;
    private final List<Mod> added;
    private final List<Changed> changed;

    private ModDiff(List<Mod> missing, List<Mod> added, List<Changed> changed) {
        this.missing = missing;
        this.added = added;
        this.changed = changed;
    }

    /**
     * Compares two mod lists. A recording without a list (made before lists were written) has nothing to compare
     * and gives an empty result.
     */
    public static ModDiff compare(Collection<String> recorded, Collection<String> installed) {
        Map<String, String> before = index(recorded);
        if (before.isEmpty()) {
            return NONE;
        }
        Map<String, String> now = index(installed);
        List<Mod> missing = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String version = now.get(entry.getKey());
            if (version == null) {
                missing.add(new Mod(entry.getKey(), entry.getValue()));
            } else if (!version.equals(entry.getValue())) {
                changed.add(new Changed(entry.getKey(), entry.getValue(), version));
            }
        }
        List<Mod> added = new ArrayList<>();
        for (Map.Entry<String, String> entry : now.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                added.add(new Mod(entry.getKey(), entry.getValue()));
            }
        }
        if (missing.isEmpty() && added.isEmpty() && changed.isEmpty()) {
            return NONE;
        }
        return new ModDiff(Collections.unmodifiableList(missing), Collections.unmodifiableList(added),
                Collections.unmodifiableList(changed));
    }

    /** Mods of the recording that are not installed, by id. */
    public List<Mod> missing() {
        return missing;
    }

    /** Mods installed now that the recording did not have, by id. */
    public List<Mod> added() {
        return added;
    }

    /** Mods installed in another version, by id. */
    public List<Changed> changed() {
        return changed;
    }

    public boolean isEmpty() {
        return missing.isEmpty() && added.isEmpty() && changed.isEmpty();
    }

    /** Number of mods in the three groups. */
    public int size() {
        return missing.size() + added.size() + changed.size();
    }

    /** The three groups as log lines, missing first: {@code missing: id version}, {@code updated: id a -> b}. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (Mod mod : missing) {
            lines.add("missing: " + label(mod));
        }
        for (Mod mod : added) {
            lines.add("added: " + label(mod));
        }
        for (Changed mod : changed) {
            lines.add("updated: " + mod.id() + " " + mod.recorded() + " -> " + mod.installed());
        }
        return lines;
    }

    /** {@code id version}, or the id alone without a version. */
    public static String label(Mod mod) {
        return mod.version().isEmpty() ? mod.id() : mod.id() + " " + mod.version();
    }

    /**
     * True for mods without effect on a replay: Just Replay, the game, Java, the loaders, the mixin library they
     * bundle, and Fabric API's modules (their ids end with a version such as {@code -v1}).
     */
    public static boolean ignored(String id) {
        switch (id) {
            case "justreplay", "minecraft", "java", "neoforge", "forge", "fabricloader", "fabric-loader", "mixinextras",
                 "fabric-api-base", "fabric-renderer-indigo" -> {
                return true;
            }
            default -> {
                return id.startsWith("fabric-") && id.matches("fabric-.+-v\\d+");
            }
        }
    }

    /** Entries by id (lower case), without the ignored mods; the last entry wins when an id comes twice. */
    private static Map<String, String> index(Collection<String> entries) {
        Map<String, String> byId = new TreeMap<>();
        if (entries == null) {
            return byId;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String text = entry.trim();
            int at = text.indexOf('@');
            String id = (at < 0 ? text : text.substring(0, at)).trim().toLowerCase(Locale.ROOT);
            String version = at < 0 ? "" : text.substring(at + 1).trim();
            if (!id.isEmpty() && !ignored(id)) {
                byId.put(id, version);
            }
        }
        return byId;
    }
}
