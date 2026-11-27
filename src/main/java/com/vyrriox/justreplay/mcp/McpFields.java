package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The editable fields of one kind of project element (a camera, an actor, an overlay...), each with its type, range
 * and description. They give both the JSON Schema of an edit tool and the strict reading of its arguments: a value of
 * the wrong type or out of range is refused with a message naming the field and what it takes, and nothing changes
 * until every field given is valid. A field left out keeps its value, so none has a default in the schema.
 *
 * @param <T> the element the fields belong to
 * @author vyrriox
 */
final class McpFields<T> {
    /** Writes a checked value into an element. */
    @FunctionalInterface
    interface Setter<T, V> {
        void set(T target, V value);
    }

    /** Reads and checks one argument; only called when the argument is there. */
    @FunctionalInterface
    interface Reader {
        Object read(McpArgs args, String name) throws McpArgs.Invalid;
    }

    private record Field<T>(String name, Consumer<McpSchema> schema, Reader reader, Setter<T, Object> setter) {
    }

    private final List<Field<T>> fields = new ArrayList<>();
    private final Set<String> names = new LinkedHashSet<>();

    McpFields<T> number(String name, String description, double min, double max, Setter<T, Double> setter) {
        return add(name, s -> s.number(name, description, min, max), (args, n) -> args.number(n, min, max), setter);
    }

    McpFields<T> integer(String name, String description, int min, int max, Setter<T, Integer> setter) {
        return add(name, s -> s.integer(name, description, min, max), (args, n) -> args.integer(n, 0, min, max), setter);
    }

    McpFields<T> bool(String name, String description, Setter<T, Boolean> setter) {
        return add(name, s -> s.bool(name, description), (args, n) -> args.bool(n, false), setter);
    }

    /** A text of at most {@code maxLength} characters; an empty one is allowed (it clears the field). */
    McpFields<T> text(String name, String description, int maxLength, Setter<T, String> setter) {
        return add(name, s -> s.string(name, description), (args, n) -> args.text(n, maxLength), setter);
    }

    /** One of {@code values}, matched without case and written as in {@code values}. */
    McpFields<T> choice(String name, String description, List<String> values, Setter<T, String> setter) {
        return add(name, s -> s.choice(name, description, null, values.toArray(new String[0])),
                (args, n) -> args.choice(n, null, values), setter);
    }

    /** An RGB colour written {@code #RRGGBB}. */
    McpFields<T> color(String name, String description, Setter<T, Integer> setter) {
        return add(name, s -> s.color(name, description), (args, n) -> color(args, n), setter);
    }

    /** A field of its own kind: its schema, its reader and its setter. */
    @SuppressWarnings("unchecked")
    <V> McpFields<T> custom(String name, Consumer<McpSchema> schema, Reader reader, Setter<T, V> setter) {
        if (!names.add(name)) {
            throw new IllegalArgumentException("Field " + name + " added twice");
        }
        fields.add(new Field<>(name, schema, reader, (Setter<T, Object>) setter));
        return this;
    }

    @SuppressWarnings("unchecked")
    private <V> McpFields<T> add(String name, Consumer<McpSchema> schema, Reader reader, Setter<T, V> setter) {
        return custom(name, schema, reader, setter);
    }

    /** Adds every field to a tool's schema, in the order they were declared. */
    McpSchema addTo(McpSchema schema) {
        for (Field<T> f : fields) {
            f.schema().accept(schema);
        }
        return schema;
    }

    Set<String> names() {
        return Collections.unmodifiableSet(names);
    }

    /** The fields given in {@code args}, read and checked; nothing is written yet. */
    Changes<T> read(McpArgs args) throws McpArgs.Invalid {
        List<Field<T>> given = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Field<T> f : fields) {
            if (args.has(f.name())) {
                given.add(f);
                values.add(f.reader().read(args, f.name()));
            }
        }
        return new Changes<>(given, values);
    }

    /** Checked values of some fields, written into an element by {@link #applyTo}. */
    static final class Changes<T> {
        private final List<Field<T>> fields;
        private final List<Object> values;

        private Changes(List<Field<T>> fields, List<Object> values) {
            this.fields = fields;
            this.values = values;
        }

        boolean isEmpty() {
            return fields.isEmpty();
        }

        boolean has(String name) {
            for (Field<T> f : fields) {
                if (f.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        /** Names of the fields given, in the order they were declared. */
        List<String> names() {
            List<String> out = new ArrayList<>();
            for (Field<T> f : fields) {
                out.add(f.name());
            }
            return out;
        }

        /** Writes the values; called on a copy first when the element must then pass checks of its own. */
        void applyTo(T target) {
            for (int i = 0; i < fields.size(); i++) {
                fields.get(i).setter().set(target, values.get(i));
            }
        }
    }

    // ------------------------------------------------------------------ readers

    /** An RGB colour {@code #RRGGBB} (the {@code #} may be left out). */
    static int color(McpArgs args, String name) throws McpArgs.Invalid {
        String value = args.string(name, null);
        if (value != null) {
            String hex = value.trim().startsWith("#") ? value.trim().substring(1) : value.trim();
            if (hex.matches("[0-9A-Fa-f]{6}")) {
                return Integer.parseInt(hex, 16);
            }
        }
        throw new McpArgs.Invalid("Argument '" + name + "' must be a colour written #RRGGBB, such as #FFD54F");
    }

    /** A colour as the answers write it. */
    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** True when the argument is a JSON object, for the arguments that take a map of values. */
    static boolean isObject(JsonElement e) {
        return e != null && e.isJsonObject();
    }
}
