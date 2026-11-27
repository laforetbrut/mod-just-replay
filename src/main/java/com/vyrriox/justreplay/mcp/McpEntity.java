package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One entity of the replay world at a tick, as the game side reads it for the observation tools: plain data, turned
 * into the JSON the tools answer with. Positions are those the recording gives at the tick (the game draws its
 * entities a little behind them, easing between recorded positions).
 *
 * @author vyrriox
 */
public final class McpEntity {
    /** An entity of the recording. */
    public static final String RECORDED = "recorded";
    /** An actor added in the editor. */
    public static final String ACTOR = "actor";
    public static final List<String> SOURCES = List.of(RECORDED, ACTOR);
    /** No vehicle. */
    public static final int NONE = Integer.MIN_VALUE;
    /** Longest name given back. */
    static final int MAX_TEXT = 64;
    /** Most equipment slots given back. */
    static final int MAX_ITEMS = 12;
    /** Most passengers given back. */
    static final int MAX_PASSENGERS = 16;

    /** Network id in the replay world (actors have negative ids, which change when they are rebuilt). */
    public int id;
    public String uuid = "";
    /** Registry id of the type, such as {@code minecraft:pig}. */
    public String type = "";
    /** The name the game shows. */
    public String name = "";
    /** Name given by a name tag or a command, empty when none. */
    public String customName = "";
    /** {@link #RECORDED} or {@link #ACTOR}. */
    public String source = RECORDED;
    /** Id of the project actor of an {@link #ACTOR}. */
    public String actorId = "";
    /** The player who recorded the replay. */
    public boolean recorder;
    public boolean player;
    public boolean living;
    public double x, y, z;
    public double yaw, pitch, headYaw;
    /** Motion in blocks per tick as the game last saw it; replaced by the recorded motion when both ticks are known. */
    public double vx, vy, vz;
    public float health = -1.0F;
    public float maxHealth = -1.0F;
    /** Pose name, such as {@code STANDING} or {@code CROUCHING}. */
    public String pose = "";
    public boolean onGround, sneaking, sprinting, swimming, invisible, glowing, onFire, baby, sleeping;
    public double width, height, eyeHeight;
    public List<Item> equipment = new ArrayList<>();
    /** Id of the entity this one rides, or {@link #NONE}. */
    public int vehicle = NONE;
    public List<Integer> passengers = new ArrayList<>();

    /** An item held or worn. */
    public record Item(String slot, String item, int count, String name) {
    }

    /**
     * How markers, cameras and the tools name this entity: {@code recorder} for the recording player,
     * {@code actor:<id>} for an actor, else its UUID.
     */
    public String ref() {
        if (recorder) {
            return com.vyrriox.justreplay.editor.Marker.RECORDER;
        }
        if (ACTOR.equals(source) && !actorId.isEmpty()) {
            return com.vyrriox.justreplay.editor.Marker.ACTOR_PREFIX + actorId;
        }
        return uuid;
    }

    /** True when {@code key} names this entity: its ref, UUID, network id, or actor id with or without its prefix. */
    public boolean matches(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String k = key.trim();
        if (k.equalsIgnoreCase(ref()) || k.equalsIgnoreCase(uuid) || k.equals(Integer.toString(id))) {
            return true;
        }
        return ACTOR.equals(source) && !actorId.isEmpty() && k.equals(actorId);
    }

    /**
     * True when the type is one of {@code types}: a full id ({@code minecraft:pig}), a name without namespace
     * ({@code pig}) or a whole mod ({@code create:*}); any case.
     */
    public boolean ofType(List<String> types) {
        if (types == null || types.isEmpty()) {
            return true;
        }
        String full = type.toLowerCase(Locale.ROOT);
        int colon = full.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : full.substring(0, colon);
        String path = colon < 0 ? full : full.substring(colon + 1);
        for (String t : types) {
            String w = t.trim().toLowerCase(Locale.ROOT);
            if (w.equals(full) || w.equals(path) || w.endsWith(":*") && w.substring(0, w.length() - 2).equals(namespace)) {
                return true;
            }
        }
        return false;
    }

    /** True when the name, custom name or type contains {@code lowerQuery}. */
    public boolean named(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) {
            return true;
        }
        return name.toLowerCase(Locale.ROOT).contains(lowerQuery) || customName.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || type.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Speed in blocks per second. */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz) * McpJson.TICKS_PER_SECOND;
    }

    /** The JSON of a list ({@code full} false) or of the detail of one entity. */
    public JsonObject toJson(boolean full) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("ref", ref());
        o.addProperty("uuid", uuid);
        o.addProperty("type", type);
        o.addProperty("name", cut(name));
        o.addProperty("source", source);
        if (ACTOR.equals(source)) {
            o.addProperty("actorId", actorId);
        }
        if (recorder) {
            o.addProperty("recorder", true);
        }
        if (player) {
            o.addProperty("player", true);
        }
        o.add("position", xyz(x, y, z, 3));
        JsonObject rotation = new JsonObject();
        rotation.addProperty("yaw", McpCamera.round(yaw, 1));
        rotation.addProperty("pitch", McpCamera.round(pitch, 1));
        if (living) {
            rotation.addProperty("headYaw", McpCamera.round(headYaw, 1));
        }
        o.add("rotation", rotation);
        JsonObject velocity = xyz(vx * McpJson.TICKS_PER_SECOND, vy * McpJson.TICKS_PER_SECOND, vz * McpJson.TICKS_PER_SECOND, 3);
        velocity.addProperty("speed", McpCamera.round(speed(), 3));
        o.add("velocity", velocity);
        if (living && health >= 0.0F) {
            o.addProperty("health", McpCamera.round(health, 1));
            o.addProperty("maxHealth", McpCamera.round(maxHealth, 1));
        }
        if (!equipment.isEmpty()) {
            JsonArray items = new JsonArray();
            for (int i = 0; i < equipment.size() && i < MAX_ITEMS; i++) {
                Item item = equipment.get(i);
                JsonObject e = new JsonObject();
                e.addProperty("slot", item.slot());
                e.addProperty("item", item.item());
                if (item.count() != 1) {
                    e.addProperty("count", item.count());
                }
                if (full && item.name() != null && !item.name().isEmpty()) {
                    e.addProperty("name", cut(item.name()));
                }
                items.add(e);
            }
            o.add("equipment", items);
        }
        if (vehicle != NONE) {
            o.addProperty("vehicle", vehicle);
        }
        if (!passengers.isEmpty()) {
            JsonArray riders = new JsonArray();
            for (int i = 0; i < passengers.size() && i < MAX_PASSENGERS; i++) {
                riders.add(passengers.get(i));
            }
            o.add("passengers", riders);
        }
        JsonArray flags = flags();
        if (flags.size() > 0) {
            o.add("flags", flags);
        }
        if (full) {
            o.addProperty("customName", cut(customName));
            o.addProperty("pose", pose);
            o.addProperty("onGround", onGround);
            JsonObject size = new JsonObject();
            size.addProperty("width", McpCamera.round(width, 3));
            size.addProperty("height", McpCamera.round(height, 3));
            size.addProperty("eyeHeight", McpCamera.round(eyeHeight, 3));
            o.add("size", size);
            o.add("eye", xyz(x, y + eyeHeight, z, 3));
        }
        return o;
    }

    private JsonArray flags() {
        JsonArray f = new JsonArray();
        if (sneaking) {
            f.add("sneaking");
        }
        if (sprinting) {
            f.add("sprinting");
        }
        if (swimming) {
            f.add("swimming");
        }
        if (sleeping) {
            f.add("sleeping");
        }
        if (invisible) {
            f.add("invisible");
        }
        if (glowing) {
            f.add("glowing");
        }
        if (onFire) {
            f.add("onFire");
        }
        if (baby) {
            f.add("baby");
        }
        return f;
    }

    static JsonObject xyz(double x, double y, double z, int digits) {
        JsonObject o = new JsonObject();
        o.addProperty("x", McpCamera.round(x, digits));
        o.addProperty("y", McpCamera.round(y, digits));
        o.addProperty("z", McpCamera.round(z, digits));
        return o;
    }

    static String cut(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }
}
