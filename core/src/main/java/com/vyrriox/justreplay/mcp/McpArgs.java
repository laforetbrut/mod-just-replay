package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The arguments of one tool call, read with their types checked. A wrong argument throws {@link Invalid}, which the
 * call answers as a tool error the caller can read and correct.
 *
 * @author vyrriox
 */
public final class McpArgs {
    private final JsonObject values;

    public McpArgs(JsonObject values) {
        this.values = values == null ? new JsonObject() : values;
    }

    /** A wrong argument: the message says which one and what it accepts. */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        public Invalid(String message) {
            super(message);
        }
    }

    /** Refuses the arguments the tool does not know, so a misspelt name is not silently ignored. */
    public void allowOnly(Set<String> names) throws Invalid {
        for (String key : values.keySet()) {
            if (!names.contains(key)) {
                throw new Invalid("Unknown argument '" + key + "'" + (names.isEmpty() ? ": this tool takes no arguments"
                        : ". Arguments of this tool: " + String.join(", ", names)));
            }
        }
    }

    public boolean has(String name) {
        JsonElement e = values.get(name);
        return e != null && !e.isJsonNull();
    }

    /** A string argument, {@code def} when it is absent or null. */
    public String string(String name, String def) throws Invalid {
        JsonElement e = values.get(name);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return e.getAsString();
        }
        throw new Invalid("Argument '" + name + "' must be a string");
    }

    /** A string argument that must be given and not blank. */
    public String required(String name) throws Invalid {
        String value = string(name, null);
        if (value == null || value.trim().isEmpty()) {
            throw new Invalid("Argument '" + name + "' is required");
        }
        return value.trim();
    }

    /** A whole number from {@code min} to {@code max}, {@code def} when absent. */
    public int integer(String name, int def, int min, int max) throws Invalid {
        JsonElement e = values.get(name);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (d == Math.rint(d) && d >= min && d <= max) {
                return (int) d;
            }
        }
        throw new Invalid("Argument '" + name + "' must be a whole number from " + min + " to " + max);
    }

    /** A whole number from {@code min} to {@code max}, or null when absent. */
    public Integer optionalInteger(String name, int min, int max) throws Invalid {
        return has(name) ? integer(name, 0, min, max) : null;
    }

    /** A number from {@code min} to {@code max}, or null when absent. */
    public Double number(String name, double min, double max) throws Invalid {
        JsonElement e = values.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (Double.isFinite(d) && d >= min && d <= max) {
                return d;
            }
        }
        throw new Invalid("Argument '" + name + "' must be a number from " + number(min) + " to " + number(max));
    }

    /** A number from {@code min} to {@code max}, {@code def} when absent. */
    public double number(String name, double def, double min, double max) throws Invalid {
        Double value = number(name, min, max);
        return value == null ? def : value;
    }

    /** A list of at most {@code maxItems} non blank strings (an array, or one string), empty when absent. */
    public List<String> strings(String name, int maxItems) throws Invalid {
        JsonElement e = values.get(name);
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        JsonArray items;
        if (e.isJsonArray()) {
            items = e.getAsJsonArray();
        } else {
            items = new JsonArray();
            items.add(e);
        }
        if (items.size() > maxItems) {
            throw new Invalid("Argument '" + name + "' takes at most " + maxItems + " values");
        }
        for (JsonElement item : items) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().trim().isEmpty()) {
                throw new Invalid("Argument '" + name + "' takes a list of non empty strings");
            }
            String value = item.getAsString().trim();
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * A list of at most {@code maxItems} points, each {@code [x, y, z]} (or an object with x, y and z), empty when
     * absent.
     */
    public List<double[]> points(String name, int maxItems) throws Invalid {
        JsonElement e = values.get(name);
        List<double[]> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (!e.isJsonArray()) {
            throw new Invalid("Argument '" + name + "' takes a list of points [x, y, z]");
        }
        JsonArray items = e.getAsJsonArray();
        if (items.size() > maxItems) {
            throw new Invalid("Argument '" + name + "' takes at most " + maxItems + " points");
        }
        for (JsonElement item : items) {
            double[] p = point(item);
            if (p == null) {
                throw new Invalid("Argument '" + name + "' takes a list of points [x, y, z] of finite numbers");
            }
            out.add(p);
        }
        return out;
    }

    private static double[] point(JsonElement item) {
        double[] p = new double[3];
        if (item.isJsonArray() && item.getAsJsonArray().size() == 3) {
            JsonArray a = item.getAsJsonArray();
            for (int i = 0; i < 3; i++) {
                if (!a.get(i).isJsonPrimitive() || !a.get(i).getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                p[i] = a.get(i).getAsDouble();
            }
        } else if (item.isJsonObject()) {
            JsonObject o = item.getAsJsonObject();
            String[] keys = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) {
                JsonElement v = o.get(keys[i]);
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                p[i] = v.getAsDouble();
            }
        } else {
            return null;
        }
        for (double v : p) {
            if (!Double.isFinite(v) || Math.abs(v) > 1.0E8) {
                return null;
            }
        }
        return p;
    }

    private static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1.0E15 ? Long.toString((long) d) : Double.toString(d);
    }

    public boolean bool(String name, boolean def) throws Invalid {
        JsonElement e = values.get(name);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        throw new Invalid("Argument '" + name + "' must be true or false");
    }

    /** One of {@code allowed}, matched without case and returned as written in {@code allowed}. */
    public String choice(String name, String def, Collection<String> allowed) throws Invalid {
        String value = string(name, null);
        if (value == null) {
            return def;
        }
        String match = match(value, allowed);
        if (match == null) {
            throw new Invalid("Argument '" + name + "' must be one of: " + String.join(", ", allowed));
        }
        return match;
    }

    /** A list of values of {@code allowed} (an array, or one string), empty when absent. */
    public List<String> choices(String name, Collection<String> allowed) throws Invalid {
        JsonElement e = values.get(name);
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        JsonArray items;
        if (e.isJsonArray()) {
            items = e.getAsJsonArray();
        } else {
            items = new JsonArray();
            items.add(e);
        }
        for (JsonElement item : items) {
            String match = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? match(item.getAsString(), allowed) : null;
            if (match == null) {
                throw new Invalid("Argument '" + name + "' takes a list of: " + String.join(", ", allowed));
            }
            if (!out.contains(match)) {
                out.add(match);
            }
        }
        return out;
    }

    private static String match(String value, Collection<String> allowed) {
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.toLowerCase(Locale.ROOT).equals(wanted)) {
                return a;
            }
        }
        return null;
    }
}
