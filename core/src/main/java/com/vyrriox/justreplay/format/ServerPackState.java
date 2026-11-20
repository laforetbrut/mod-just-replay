package com.vyrriox.justreplay.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The resource packs a server has pushed and not removed, in push order (the last one on top). Recording keeps the
 * live server's set to write it at the start of a replay and in its checkpoints; playback rebuilds the set from the
 * recorded packets and applies the difference with what it applied before. Plain Java, no game code.
 *
 * @author vyrriox
 */
public final class ServerPackState {
    /** A pushed pack: its id, address, SHA-1 (may be empty) and whether the server required it. */
    public record Pack(UUID id, String url, String hash, boolean required) {
        public Pack {
            url = url == null ? "" : url;
            hash = hash == null ? "" : hash.trim();
        }

        /** Same pack file: same id, address and hash (whether it was required changes nothing on screen). */
        public boolean sameFile(Pack other) {
            return other != null && id.equals(other.id) && url.equals(other.url) && hash.equalsIgnoreCase(other.hash);
        }
    }

    /** What applying a new set needs: the packs to remove first, then the packs to add, in that order. */
    public record Diff(List<Pack> remove, List<Pack> add) {
        public boolean isEmpty() {
            return remove.isEmpty() && add.isEmpty();
        }
    }

    private final Map<UUID, Pack> packs = new LinkedHashMap<>();

    /** A server pushed a pack; pushing an id again replaces it and puts it on top. */
    public synchronized void push(Pack pack) {
        if (pack == null || pack.id() == null) {
            return;
        }
        packs.remove(pack.id());
        packs.put(pack.id(), pack);
    }

    /** Versions with a single server pack (1.20.1): a new pack replaces the previous one. */
    public synchronized void replaceAll(Pack pack) {
        packs.clear();
        push(pack);
    }

    /** A server removed one pack, or all of them when {@code id} is null. */
    public synchronized void pop(UUID id) {
        if (id == null) {
            packs.clear();
        } else {
            packs.remove(id);
        }
    }

    public synchronized void clear() {
        packs.clear();
    }

    /** The packs in push order. */
    public synchronized List<Pack> packs() {
        return List.copyOf(packs.values());
    }

    public synchronized boolean isEmpty() {
        return packs.isEmpty();
    }

    /**
     * Goes from the packs applied now to the wanted ones: removes the packs no longer wanted or whose file changed,
     * adds the wanted packs that are missing (or were just removed for a new file), both in their own order.
     */
    public static Diff diff(List<Pack> applied, List<Pack> wanted) {
        List<Pack> remove = new ArrayList<>();
        List<Pack> add = new ArrayList<>();
        for (Pack pack : applied) {
            Pack want = find(wanted, pack.id());
            if (want == null || !want.sameFile(pack)) {
                remove.add(pack);
            }
        }
        for (Pack want : wanted) {
            Pack have = find(applied, want.id());
            if (have == null || !have.sameFile(want)) {
                add.add(want);
            }
        }
        return new Diff(List.copyOf(remove), List.copyOf(add));
    }

    private static Pack find(List<Pack> packs, UUID id) {
        for (Pack pack : packs) {
            if (pack.id().equals(id)) {
                return pack;
            }
        }
        return null;
    }

    /** True for a SHA-1 written as 40 hexadecimal digits, in either case. */
    public static boolean isSha1(String hash) {
        return hash != null && hash.length() == 40 && hash.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    /** The hash as the game names its cached pack files (lower case), or null when it is not a SHA-1. */
    public static String cacheName(String hash) {
        return isSha1(hash) ? hash.toLowerCase(Locale.ROOT) : null;
    }

    /** Id given to the single pack of versions without pack ids (1.20.1): the same address always gets the same id. */
    public static UUID idOf(String url) {
        return UUID.nameUUIDFromBytes(("justreplay:server-pack:" + (url == null ? "" : url)).getBytes(StandardCharsets.UTF_8));
    }
}
