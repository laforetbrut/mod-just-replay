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
