package com.vyrriox.justreplay.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A marker of the editor: a named note pinned to a moment, a stretch of time, a point of the world or an entity.
 * The note says what should happen there, for the person editing the replay or for a tool that drives the editor.
 * Saved in {@code project.json}; markers of projects written before these fields existed held a tick and a name,
 * and load as {@link #INSTANT} markers.
 *
 * <p>Times are replay ticks. A marker covers {@link #tick} alone, or {@link #tick} to {@link #end} (both included)
 * when it has an end: always for a {@link #RANGE}, optionally for a {@link #POINT} or an {@link #ENTITY}.
 *
 * @author vyrriox
 */
public final class Marker {
    /** A moment of the replay. */
    public static final String INSTANT = "INSTANT";
    /** A stretch of the replay, from {@link #tick} to {@link #end}. */
    public static final String RANGE = "RANGE";
    /** A point of the world: {@link #x}, {@link #y}, {@link #z} in {@link #dimension}. */
    public static final String POINT = "POINT";
    /** An entity: {@link #target}. */
    public static final String ENTITY = "ENTITY";
    public static final List<String> TYPES = List.of(INSTANT, RANGE, POINT, ENTITY);

    /** {@link #target} of the player who recorded the replay. */
    public static final String RECORDER = "recorder";
    /** Prefix of a {@link #target} naming a project actor ({@code actor:<id>}) rather than a recorded entity. */
    public static final String ACTOR_PREFIX = "actor:";

    /** Longest name kept, the length of the editor's text field. */
    public static final int MAX_NAME = 128;
    /** Longest note kept. */
    public static final int MAX_NOTE = 2000;
    /** Length given to a new range, 5 seconds. */
    public static final int DEFAULT_LENGTH = 100;

    /** Preset colours (RGB), with the translation key suffix of each. */
    public static final int[] COLORS = {0xFFD54F, 0xFF8A65, 0xFF6B6B, 0xF06292, 0xBA68C8, 0x5AB4FF, 0x4DD0E1, 0x5BE49B, 0xFFFFFF};
    public static final String[] COLOR_NAMES = {"gold", "orange", "red", "pink", "purple", "blue", "aqua", "green", "white"};

    /** Result of {@link #checkRange}. */
    public enum RangeCheck {
        OK,
        /** The start is before the replay. */
        NEGATIVE_START,
        /** The end is not after the start. */
        EMPTY,
        /** The end is past the end of the replay. */
        PAST_END
    }

    /** Stable id, kept by every edit; never empty once normalized. */
    public String id = "";
    public String name = "";
    /** Free note: what should happen here. */
    public String note = "";
    /** One of {@link #TYPES}. */
    public String type = INSTANT;
    /** First tick. */
    public int tick;
    /** Last tick (included) of a marker that covers a stretch of time; -1 for a single moment. */
    public int end = -1;
    /** Position of a {@link #POINT}. */
    public double x, y, z;
    /** Dimension of a {@link #POINT}, such as {@code minecraft:overworld}; empty for any dimension. */
    public String dimension = "";
    /**
     * Entity of an {@link #ENTITY} marker: the UUID of a recorded entity, {@link #RECORDER}, or {@link #ACTOR_PREFIX}
     * followed by the id of a project actor.
     */
    public String target = "";
    /** Readable name of {@link #target} when it was picked. */
    public String targetName = "";
    /** Colour, RGB. */
    public int color = defaultColor(INSTANT);

    public Marker() {
    }

    /** A marker at a tick, with a new id. */
    public static Marker instant(int tick, String name) {
        Marker m = new Marker();
        m.id = newId();
        m.name = name == null ? "" : name;
        m.tick = Math.max(0, tick);
        return m;
    }

    /**
     * A marker over {@code from} to {@code to} (both included), with a new id.
     *
     * @throws IllegalArgumentException when the range is not valid for a replay of {@code duration} ticks (-1 when
     *                                  unknown), see {@link #checkRange}
     */
    public static Marker range(int from, int to, int duration, String name) {
        RangeCheck check = checkRange(from, to, duration);
        if (check != RangeCheck.OK) {
            throw new IllegalArgumentException("Marker range " + from + ".." + to + " refused: " + check);
        }
        Marker m = instant(from, name);
        m.type = RANGE;
        m.end = to;
        m.color = defaultColor(RANGE);
        return m;
    }

    /**
     * A marker on a point of the world at a tick, with a new id.
     *
     * @throws IllegalArgumentException when a coordinate is not a finite number
     */
    public static Marker point(int tick, double x, double y, double z, String dimension, String name) {
        Marker m = instant(tick, name);
        m.color = defaultColor(POINT);
        m.placeAt(x, y, z, dimension);
        return m;
    }

    /**
     * A marker on an entity at a tick, with a new id.
     *
     * @throws IllegalArgumentException when the target is empty
     */
    public static Marker entity(int tick, String target, String targetName, String name) {
        Marker m = instant(tick, name);
        m.color = defaultColor(ENTITY);
        m.follow(target, targetName);
        return m;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Colour given to a new marker of a type. */
    public static int defaultColor(String type) {
        return switch (type == null ? INSTANT : type) {
            case RANGE -> 0xFF8A65;
            case POINT -> 0x4DD0E1;
            case ENTITY -> 0xF06292;
            default -> 0xFFD54F;
        };
    }

    /**
     * Whether {@code from} to {@code to} (both included) is a valid stretch of a replay of {@code duration} ticks
     * (-1 when unknown): it starts at 0 or later, ends after its start and not after the replay.
     */
    public static RangeCheck checkRange(int from, int to, int duration) {
        if (from < 0) {
            return RangeCheck.NEGATIVE_START;
        }
        if (to <= from) {
            return RangeCheck.EMPTY;
        }
        if (duration >= 0 && to > duration) {
            return RangeCheck.PAST_END;
        }
        return RangeCheck.OK;
    }

    public boolean isRange() {
        return end > tick;
    }

    public boolean isPoint() {
        return POINT.equals(type);
    }

    public boolean isEntity() {
        return ENTITY.equals(type);
    }

    /** Last tick covered: the end of a range, the tick itself otherwise. */
    public int lastTick() {
        return isRange() ? end : tick;
    }

    /** Ticks covered after the first one, 0 for a single moment. */
    public int length() {
        return isRange() ? end - tick : 0;
    }

    /** True when {@code time} is within the marker, widened by {@code slack} ticks on both sides. */
    public boolean activeAt(double time, double slack) {
        return time >= tick - slack && time <= lastTick() + slack;
    }

    /**
     * Makes the marker cover {@code from} to {@code to}; a moment marker becomes a {@link #RANGE}, a point or an
     * entity keeps its type. An invalid range (see {@link #checkRange}) is refused and changes nothing.
     */
    public RangeCheck setRange(int from, int to, int duration) {
        RangeCheck check = checkRange(from, to, duration);
        if (check != RangeCheck.OK) {
            return check;
        }
        tick = from;
        end = to;
        if (INSTANT.equals(type)) {
            retype(RANGE);
        }
        return check;
    }

    /** Drops the end: the marker covers its first tick only, a {@link #RANGE} becomes a moment. */
    public void clearEnd() {
        end = -1;
        if (RANGE.equals(type)) {
            retype(INSTANT);
        }
    }

    /**
     * Moves the marker to start at {@code to}, keeping its length, within a replay of {@code duration} ticks (-1 when
     * unknown). True when it moved.
     */
    public boolean moveTo(int to, int duration) {
        int length = length();
        int start = Math.max(0, to);
        if (duration >= 0) {
            start = Math.min(start, Math.max(0, duration - length));
        }
        if (start == tick) {
            return false;
        }
        tick = start;
        if (length > 0) {
            end = start + length;
        }
        return true;
    }

    /**
     * Pins the marker to a point of the world; it becomes a {@link #POINT}.
     *
     * @throws IllegalArgumentException when a coordinate is not a finite number
     */
    public void placeAt(double px, double py, double pz, String dim) {
        if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
            throw new IllegalArgumentException("Marker point " + px + " " + py + " " + pz + " refused: not a finite position");
        }
        x = px;
        y = py;
        z = pz;
        dimension = dim == null ? "" : dim.trim();
        target = "";
        targetName = "";
        retype(POINT);
    }

    /**
     * Pins the marker to an entity; it becomes an {@link #ENTITY}.
     *
     * @throws IllegalArgumentException when the target is empty
     */
    public void follow(String entity, String entityName) {
        if (entity == null || entity.isBlank()) {
            throw new IllegalArgumentException("Marker target refused: empty");
        }
        target = entity.trim();
        targetName = entityName == null ? "" : entityName;
        x = y = z = 0;
        dimension = "";
        retype(ENTITY);
    }

    /** Frees a point or an entity marker from its place: it becomes a moment, or a range when it has an end. */
    public void unpin() {
        x = y = z = 0;
        dimension = "";
        target = "";
        targetName = "";
        retype(isRange() ? RANGE : INSTANT);
    }

    /** Changes the type; a colour left at the old type's default takes the new one's. */
    private void retype(String next) {
        if (color == defaultColor(type)) {
            color = defaultColor(next);
        }
        type = next;
    }

    /** Deep copy with a new id. */
    public Marker copy() {
        Marker c = new Marker();
        c.id = newId();
        c.name = name;
        c.note = note;
        c.type = type;
        c.tick = tick;
        c.end = end;
        c.x = x;
        c.y = y;
        c.z = z;
        c.dimension = dimension;
        c.target = target;
        c.targetName = targetName;
        c.color = color;
        return c;
    }

    /**
     * Repairs a marker read from a file: missing texts, too long ones, an unknown type, a negative tick. An end that
     * is not after the tick leaves a single moment; a point without a finite position or an entity without a target
     * keeps its time only. The id is handled by {@link #normalizeAll}.
     */
    public void normalize() {
        name = clip(name, MAX_NAME);
        note = clip(note, MAX_NOTE);
        dimension = dimension == null ? "" : dimension.trim();
        target = target == null ? "" : target.trim();
        targetName = targetName == null ? "" : targetName;
        color &= 0xFFFFFF;
        tick = Math.max(0, tick);
        if (end <= tick) {
            end = -1;
        }
        if (type == null || !TYPES.contains(type)) {
            type = isRange() ? RANGE : INSTANT;
        }
        if (POINT.equals(type) && !(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
            unpin();
        } else if (ENTITY.equals(type) && target.isEmpty()) {
            unpin();
        } else if (RANGE.equals(type) && !isRange()) {
            type = INSTANT;
        } else if (INSTANT.equals(type) && isRange()) {
            type = RANGE;
        }
        if (!POINT.equals(type)) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                x = y = z = 0;
            }
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * Repairs a list of markers read from a file (null gives an empty list): drops null entries, normalizes each,
     * gives an id to those without one and sorts them by tick. A marker of an older project gets an id made from its
     * place, tick and name, so the same file always gives the same ids until it is saved; a repeated id is made new
     * the same way.
     */
    public static List<Marker> normalizeAll(List<Marker> markers) {
        List<Marker> list = markers == null ? new ArrayList<>() : markers;
        list.removeIf(m -> m == null);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Marker m = list.get(i);
            m.normalize();
            String id = m.id == null ? "" : m.id.trim();
            if (id.isEmpty()) {
                id = derivedId("marker:" + i + ":" + m.tick + ":" + m.name);
            }
            while (!seen.add(id)) {
                id = derivedId(id + ":" + i);
            }
            m.id = id;
        }
        list.sort(Comparator.comparingInt(m -> m.tick));
        return list;
    }

    private static String derivedId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The first free default name, "Marker N" from the number of markers plus one. */
    public static String nextName(List<Marker> markers) {
        Set<String> used = new HashSet<>();
        for (Marker m : markers) {
            used.add(m.name);
        }
        for (int n = markers.size() + 1; ; n++) {
            String name = "Marker " + n;
            if (!used.contains(name)) {
                return name;
            }
        }
    }
}
