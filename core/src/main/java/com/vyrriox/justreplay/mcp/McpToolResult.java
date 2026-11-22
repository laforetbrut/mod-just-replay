package com.vyrriox.justreplay.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * What a tool answers: a JSON object for programs ({@code structuredContent}) with the same JSON as text for the
 * model, or an error message the model can read ({@code isError}).
 *
 * @author vyrriox
 */
public final class McpToolResult {
    /** Compact JSON: what the model reads costs tokens. */
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final JsonObject structured;
    private final String text;
    private final boolean error;

    private McpToolResult(JsonObject structured, String text, boolean error) {
        this.structured = structured;
        this.text = text;
        this.error = error;
    }

    public static McpToolResult of(JsonObject structured) {
        return new McpToolResult(structured, GSON.toJson(structured), false);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(null, message, true);
    }

    public boolean isError() {
        return error;
    }

    public String text() {
        return text;
    }

    /** The JSON result, or null for an error. */
    public JsonObject structured() {
        return structured;
    }

    JsonObject toJson() {
        JsonObject out = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        content.add(block);
        out.add("content", content);
        if (structured != null) {
            out.add("structuredContent", structured);
        }
        out.addProperty("isError", error);
        return out;
    }
}
