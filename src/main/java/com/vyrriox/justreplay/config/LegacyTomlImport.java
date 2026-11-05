package com.vyrriox.justreplay.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the values of the TOML file older versions wrote, once, when the JSON file does not exist yet. The old file
 * only ever holds {@code [section]} headers and {@code key = value} lines with a boolean, a number or a string, so a
 * line parser is enough. The old file stays where it is and is no longer read afterwards.
 *
 * @author vyrriox
 */
final class LegacyTomlImport {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyTomlImport.class);

    private LegacyTomlImport() {
    }

    static void apply(ConfigFile target, Path toml) {
        try {
            String section = "";
            int imported = 0;
            for (String raw : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (line.charAt(0) == '[') {
                    int end = line.indexOf(']');
                    section = end < 0 ? "" : line.substring(1, end).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && store(target, section, line.substring(0, eq).trim(), line.substring(eq + 1).trim())) {
                    imported++;
                }
            }
            LOGGER.info("Imported {} settings from {}", imported, toml.getFileName());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not import the old settings from {}: {}", toml.getFileName(), e.toString());
        }
    }

    private static boolean store(ConfigFile target, String sectionName, String key, String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (ConfigFile.Section section : target.sections()) {
            if (section.name().equals(sectionName)) {
                ConfigFile.Value<?> value = section.find(unquote(key));
                if (value != null) {
                    value.read(parse(text));
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonElement parse(String text) {
        char first = text.charAt(0);
        if (first == '"' || first == '\'') {
            return new JsonPrimitive(unquote(text));
        }
        int comment = text.indexOf('#');
        String bare = (comment < 0 ? text : text.substring(0, comment)).trim();
        if (bare.equals("true") || bare.equals("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(bare));
        }
        try {
            return new JsonPrimitive(Double.parseDouble(bare));
        } catch (NumberFormatException e) {
            return new JsonPrimitive(bare);
        }
    }

    /** Text of a basic (double quotes, with escapes) or literal (single quotes) string; what follows it is ignored. */
    private static String unquote(String text) {
        if (text.isEmpty() || (text.charAt(0) != '"' && text.charAt(0) != '\'')) {
            return text;
        }
        char quote = text.charAt(0);
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) {
                break;
            }
            if (c == '\\' && quote == '"' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
