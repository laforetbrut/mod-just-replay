package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the JSON Schema of a tool's arguments: an object with named properties and no other ones.
 *
 * @author vyrriox
 */
public final class McpSchema {
    private final JsonObject properties = new JsonObject();
    private final Set<String> names = new LinkedHashSet<>();
    private final Set<String> required = new LinkedHashSet<>();

    private McpSchema() {
    }

    /** An object schema; with no property added it describes a tool that takes no arguments. */
    public static McpSchema object() {
        return new McpSchema();
    }

    public McpSchema string(String name, String description) {
        property(name, "string", description);
        return this;
    }

    public McpSchema integer(String name, String description, int min, int max, int def) {
        JsonObject p = property(name, "integer", description);
        p.addProperty("minimum", min);
        p.addProperty("maximum", max);
        p.addProperty("default", def);
        return this;
    }

    public McpSchema bool(String name, String description, boolean def) {
        JsonObject p = property(name, "boolean", description);
        p.addProperty("default", def);
        return this;
    }

    /** A string that must be one of {@code values}. */
    public McpSchema choice(String name, String description, String def, String... values) {
        JsonObject p = property(name, "string", description);
        p.add("enum", array(values));
        if (def != null) {
            p.addProperty("default", def);
        }
        return this;
    }

    /** A list of strings, each one of {@code values}. */
    public McpSchema choices(String name, String description, String... values) {
        JsonObject p = property(name, "array", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        items.add("enum", array(values));
        p.add("items", items);
        p.addProperty("uniqueItems", true);
        return this;
    }

    public McpSchema required(String... required) {
        for (String name : required) {
            if (!names.contains(name)) {
                throw new IllegalArgumentException("Unknown property " + name);
            }
            this.required.add(name);
        }
        return this;
    }

    /** Names of the properties, in the order they were added. */
    public Set<String> names() {
        return Collections.unmodifiableSet(names);
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties.deepCopy());
        if (!required.isEmpty()) {
            schema.add("required", array(required.toArray(new String[0])));
        }
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private JsonObject property(String name, String type, String description) {
        if (!names.add(name)) {
            throw new IllegalArgumentException("Property " + name + " added twice");
        }
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        properties.add(name, p);
        return p;
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
