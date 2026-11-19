package com.vyrriox.justreplay.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.vyrriox.justreplay.platform.EarlyMods;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The switch of each integration, from the {@code compat} section of the client config ({@code compat.<mod id>}, on
 * by default). Read once, before the mixins are chosen, straight from the file: the config classes need the game,
 * which is not there yet. The settings screen writes the same section; a change applies at the next start.
 *
 * @author vyrriox
 */
public final class CompatSwitches {
    /** The client config file, in the loader's config folder. */
    public static final String FILE_NAME = "justreplay-client.json";
    /** Section of the client config that holds the switches. */
    public static final String SECTION = "compat";

    private static volatile Map<String, Boolean> switches;

    private CompatSwitches() {
    }

    /** False when the config turns the integration with this mod off. */
    public static boolean isEnabled(String modId) {
        Map<String, Boolean> read = switches;
        if (read == null) {
            read = read(EarlyMods.configDir().resolve(FILE_NAME));
            switches = read;
        }
        return read.getOrDefault(modId, Boolean.TRUE);
    }

    /** The switches of this config file; none (every integration on) when it is missing or unreadable. */
    static Map<String, Boolean> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LoggerFactory.getLogger(CompatSwitches.class).warn("Cannot read the integration switches of {}: {}", file, t.toString());
            return Map.of();
        }
    }

    /**
     * The switches of a config text (JSON with {@code //} comments, as the config writes it). A value that is not
     * {@code true} or {@code false} keeps its integration on; the config replaces it when it loads.
     */
    static Map<String, Boolean> parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        JsonElement root;
        try {
            // A JsonReader is lenient: the // comments of the file are accepted.
            root = JsonParser.parseReader(new JsonReader(new StringReader(text)));
        } catch (Throwable t) {
            return Map.of();
        }
        JsonElement section = root.isJsonObject() ? root.getAsJsonObject().get(SECTION) : null;
        if (section == null || !section.isJsonObject()) {
            return Map.of();
        }
        Map<String, Boolean> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : ((JsonObject) section).entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                out.put(entry.getKey(), value.getAsBoolean());
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
