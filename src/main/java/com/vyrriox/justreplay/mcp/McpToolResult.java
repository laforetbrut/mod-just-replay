package com.vyrriox.justreplay.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * What a tool answers: a JSON object for programs ({@code structuredContent}) with the same JSON as text for the
 * model, possibly after a picture, or an error message the model can read ({@code isError}).
 *
 * @author vyrriox
 */
public final class McpToolResult {
    /** Compact JSON: what the model reads costs tokens. */
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final JsonObject structured;
    private final String text;
    private final boolean error;
    private final byte[] image;
    private final String imageType;

    private McpToolResult(JsonObject structured, String text, boolean error, byte[] image, String imageType) {
        this.structured = structured;
        this.text = text;
        this.error = error;
        this.image = image;
        this.imageType = imageType;
    }

    public static McpToolResult of(JsonObject structured) {
        return new McpToolResult(structured, GSON.toJson(structured), false, null, null);
    }

    /** A picture for the model ({@code image} content, base64) followed by what it shows as JSON. */
    public static McpToolResult image(byte[] data, String mimeType, JsonObject structured) {
        return new McpToolResult(structured, GSON.toJson(structured), false, data.clone(), mimeType);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(null, message, true, null, null);
    }

    /** The picture of an {@link #image} result, or null. */
    public byte[] imageData() {
        return image == null ? null : image.clone();
    }

    public String imageType() {
        return imageType;
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
        if (image != null) {
            JsonObject picture = new JsonObject();
            picture.addProperty("type", "image");
            picture.addProperty("data", java.util.Base64.getEncoder().encodeToString(image));
            picture.addProperty("mimeType", imageType);
            content.add(picture);
        }
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
