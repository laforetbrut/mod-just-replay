package com.vyrriox.justreplay.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which entities of a tick a list gives back: types, source, a text in the name, a sphere around a centre, then one
 * page of them, nearest first when there is a centre, by network id otherwise.
 *
 * @param types  types kept, all when empty (see {@link McpEntity#ofType})
 * @param source {@link McpEntity#RECORDED}, {@link McpEntity#ACTOR} or null for both
 * @param query  text the name, custom name or type contains, any case; empty keeps all
 * @param center centre {x, y, z} of the sphere, or null
 * @param radius radius of the sphere in blocks, used with a centre
 * @author vyrriox
 */
public record McpEntityQuery(List<String> types, String source, String query, double[] center, double radius, int offset, int limit) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    /** Most entities of a page in full detail. */
    public static final int MAX_FULL_LIMIT = 50;
    public static final double DEFAULT_RADIUS = 32.0;
    public static final double MAX_RADIUS = 4096.0;

    public McpEntityQuery {
        types = types == null ? List.of() : List.copyOf(types);
        query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        radius = Math.max(0.0, Math.min(MAX_RADIUS, radius));
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    /**
     * One page of the entities kept.
     *
     * @param total      entities kept by the filters, over all pages
     * @param nextOffset offset of the next page, or -1 on the last one
     * @param distances  distance of each entity of {@link #entities} to the centre, empty without one
     */
    public record Page(int total, int offset, int nextOffset, List<McpEntity> entities, List<Double> distances) {
    }

    public boolean keeps(McpEntity e) {
        if (source != null && !source.equals(e.source)) {
            return false;
        }
        if (!e.ofType(types) || !e.named(query)) {
            return false;
        }
        return center == null || e.distanceTo(center[0], center[1], center[2]) <= radius;
    }

    public Page apply(List<McpEntity> all) {
        List<McpEntity> kept = new ArrayList<>();
        for (McpEntity e : all) {
            if (keeps(e)) {
                kept.add(e);
            }
        }
        if (center == null) {
            kept.sort(Comparator.comparingInt((McpEntity e) -> e.id));
        } else {
            kept.sort(Comparator.comparingDouble((McpEntity e) -> e.distanceTo(center[0], center[1], center[2]))
                    .thenComparingInt(e -> e.id));
        }
        List<McpEntity> page = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int i = offset; i < kept.size() && page.size() < limit; i++) {
            McpEntity e = kept.get(i);
            page.add(e);
            if (center != null) {
                distances.add(e.distanceTo(center[0], center[1], center[2]));
            }
        }
        int next = offset + page.size() < kept.size() ? offset + page.size() : -1;
        return new Page(kept.size(), offset, next, page, distances);
    }

    /**
     * Puts the recorded motion into the entities of a tick, from where the same entities were one tick earlier:
     * the motion the game keeps for an entity of a recording is often zero. Entities missing from {@code before}
     * keep what the game gave.
     */
    public static void motionFrom(List<McpEntity> before, List<McpEntity> now) {
        if (before == null) {
            return;
        }
        Map<String, McpEntity> previous = new HashMap<>();
        for (McpEntity e : before) {
            previous.put(e.ref(), e);
        }
        for (McpEntity e : now) {
            McpEntity p = previous.get(e.ref());
            if (p != null) {
                e.vx = e.x - p.x;
                e.vy = e.y - p.y;
                e.vz = e.z - p.z;
            }
        }
    }
}
