package com.vyrriox.justreplay.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A small, dependency-free configuration file: JSON with {@code //} comments, grouped in sections.
 * <p>
 * Every value has a default, a comment and (for numbers) a range. Missing or invalid values are
 * replaced by their default and the file is rewritten, so the file on disk always documents every
 * option. Works the same on every loader because it only relies on Gson, which Minecraft bundles.
 *
 * @author vyrriox
 */
public final class ConfigFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigFile.class);
    private static final Gson GSON = new Gson();

    private final Path path;
    private final List<String> header;
    private final List<Section> sections = new ArrayList<>();
    private final List<Consumer<ConfigFile>> listeners = new ArrayList<>();
    /** Above zero inside {@link #batch}: saving waits for its end. */
    private int batchDepth;
    private boolean batchDirty;

    public ConfigFile(Path path, String... header) {
        this.path = path;
        this.header = List.of(header);
    }

    public Path path() {
        return path;
    }

    public Section section(String name, String comment) {
        Section section = new Section(this, name, comment);
        sections.add(section);
        return section;
    }

    public List<Section> sections() {
        return Collections.unmodifiableList(sections);
    }

    public void onLoad(Consumer<ConfigFile> listener) {
        listeners.add(listener);
    }

    /** Reads the file (creating it when missing), validates every value and rewrites it if needed. */
    public synchronized void load() {
        boolean rewrite = false;
        JsonObject root = null;
        if (Files.isRegularFile(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseReader(new JsonReader(new StringReader(stripBom(text))));
                if (parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject();
                } else {
                    LOGGER.warn("Config {} is not a JSON object, restoring defaults", path);
                }
            } catch (Exception e) {
                LOGGER.error("Config {} could not be parsed ({}). A backup is kept and defaults are used.", path, e.getMessage());
                backupBroken();
            }
        }
        if (root == null) {
            root = new JsonObject();
            rewrite = true;
        }
        for (Section section : sections) {
            JsonElement sectionElement = root.get(section.name);
            JsonObject sectionObject = sectionElement != null && sectionElement.isJsonObject() ? sectionElement.getAsJsonObject() : null;
            if (sectionObject == null) {
                rewrite = true;
            }
            for (Value<?> value : section.values) {
                JsonElement element = sectionObject == null ? null : sectionObject.get(value.key);
                if (element == null) {
                    value.reset();
                    rewrite = true;
                    continue;
                }
                if (!value.read(element)) {
                    LOGGER.warn("Config {}: invalid value for {}.{} ({}), using {}", path.getFileName(), section.name, value.key, element, value.describeValue());
                    rewrite = true;
                }
            }
            if (sectionObject != null) {
                for (String key : sectionObject.keySet()) {
                    if (section.find(key) == null) {
                        LOGGER.warn("Config {}: unknown option {}.{} ignored", path.getFileName(), section.name, key);
                    }
                }
            }
        }
        if (rewrite) {
            save();
        }
        for (Consumer<ConfigFile> listener : listeners) {
            listener.accept(this);
        }
    }

    /** Runs several {@link Value#set} calls and writes the file once at the end. */
    public synchronized void batch(Runnable changes) {
        batchDepth++;
        try {
            changes.run();
        } finally {
            batchDepth--;
            if (batchDepth == 0 && batchDirty) {
                batchDirty = false;
                save();
            }
        }
    }

    private synchronized void changed() {
        if (batchDepth > 0) {
            batchDirty = true;
        } else {
            save();
        }
    }

    public synchronized void save() {
        StringBuilder out = new StringBuilder();
        for (String line : header) {
            out.append("// ").append(line).append('\n');
        }
        out.append("// Lines starting with // are comments. Delete a line to restore its default value.\n");
        out.append("{\n");
        for (int s = 0; s < sections.size(); s++) {
            Section section = sections.get(s);
            out.append('\n');
            appendComment(out, "  ", section.comment);
            out.append("  ").append(GSON.toJson(section.name)).append(": {\n");
            for (int v = 0; v < section.values.size(); v++) {
                Value<?> value = section.values.get(v);
                appendComment(out, "    ", value.comment);
                out.append("    // ").append(value.describeRange()).append('\n');
                out.append("    ").append(GSON.toJson(value.key)).append(": ").append(value.writeCurrent());
                out.append(v + 1 < section.values.size() ? ",\n" : "\n");
            }
            out.append("  }").append(s + 1 < sections.size() ? ",\n" : "\n");
        }
        out.append("}\n");
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Could not write config {}", path, e);
        }
    }

    private void backupBroken() {
        try {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Nothing else we can do; defaults will overwrite the broken file.
        }
    }

    private static void appendComment(StringBuilder out, String indent, String comment) {
        if (comment == null || comment.isEmpty()) {
            return;
        }
        for (String line : comment.split("\n")) {
            out.append(indent).append("// ").append(line).append('\n');
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    /** A named group of values. */
    public static final class Section {
        private final ConfigFile owner;
        private final String name;
        private final String comment;
        private final List<Value<?>> values = new ArrayList<>();

        private Section(ConfigFile owner, String name, String comment) {
            this.owner = owner;
            this.name = name;
            this.comment = comment;
        }

        public String name() {
            return name;
        }

        public String comment() {
            return comment;
        }

        public List<Value<?>> values() {
            return Collections.unmodifiableList(values);
        }

        Value<?> find(String key) {
            for (Value<?> value : values) {
                if (value.key.equals(key)) {
                    return value;
                }
            }
            return null;
        }

        private <V extends Value<?>> V add(V value) {
            ((Value<?>) value).owner = owner;
            values.add(value);
            return value;
        }

        public BoolValue bool(String key, boolean def, String comment) {
            return add(new BoolValue(key, def, comment));
        }

        public IntValue integer(String key, int def, int min, int max, String comment) {
            return add(new IntValue(key, def, min, max, comment));
        }

        public DoubleValue decimal(String key, double def, double min, double max, String comment) {
            return add(new DoubleValue(key, def, min, max, comment));
        }

        public StringValue string(String key, String def, String comment) {
            return add(new StringValue(key, def, comment));
        }

        public StringListValue list(String key, List<String> def, String comment) {
            return add(new StringListValue(key, def, comment));
        }

        public <E extends Enum<E>> EnumValue<E> enumeration(String key, E def, String comment) {
            return add(new EnumValue<>(key, def, comment));
        }
    }

    /** One configurable value. */
    public abstract static class Value<T> {
        protected final String key;
        protected final String comment;
        protected final T def;
        protected volatile T value;
        private ConfigFile owner;

        protected Value(String key, T def, String comment) {
            this.key = key;
            this.def = def;
            this.comment = comment;
            this.value = def;
        }

        public String key() {
            return key;
        }

        public String comment() {
            return comment;
        }

        public T get() {
            return value;
        }

        public T defaultValue() {
            return def;
        }

        /** Changes the value and writes the file when the value really changed. */
        public void set(T newValue) {
            T next = newValue == null ? def : newValue;
            if (Objects.equals(value, next)) {
                return;
            }
            this.value = next;
            if (owner != null) {
                owner.changed();
            }
        }

        void reset() {
            value = def;
        }

        /** Parses the element into this value. Returns false when a correction was needed. */
        abstract boolean read(JsonElement element);

        abstract JsonElement toJson(T v);

        abstract String describeRange();

        String writeCurrent() {
            return GSON.toJson(toJson(value));
        }

        String describeValue() {
            return String.valueOf(value);
        }
    }

    public static final class BoolValue extends Value<Boolean> {
        BoolValue(String key, boolean def, String comment) {
            super(key, def, comment);
        }

        public boolean isTrue() {
            return value;
        }

        @Override
        boolean read(JsonElement element) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
                value = element.getAsBoolean();
                return true;
            }
            if (element.isJsonPrimitive()) {
                String s = element.getAsString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("yes") || s.equals("on")) {
                    value = true;
                    return false;
                }
                if (s.equals("false") || s.equals("no") || s.equals("off")) {
                    value = false;
                    return false;
                }
            }
            value = def;
            return false;
        }

        @Override
        JsonElement toJson(Boolean v) {
            return new JsonPrimitive(v);
        }

        @Override
        String describeRange() {
            return "true or false. Default: " + def;
        }
    }

    public static final class IntValue extends Value<Integer> {
        private final int min;
        private final int max;

        IntValue(String key, int def, int min, int max, String comment) {
            super(key, def, comment);
            this.min = min;
            this.max = max;
        }

        public int getInt() {
            return value;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        public void set(Integer newValue) {
            super.set(newValue == null ? def : Math.max(min, Math.min(max, newValue)));
        }

        @Override
        boolean read(JsonElement element) {
            try {
                double raw = element.getAsDouble();
                int parsed = (int) Math.round(raw);
                int clamped = Math.max(min, Math.min(max, parsed));
                value = clamped;
                return clamped == raw;
            } catch (RuntimeException e) {
                value = def;
                return false;
            }
        }

        @Override
        JsonElement toJson(Integer v) {
            return new JsonPrimitive(v);
        }

        @Override
        String describeRange() {
            return "Range: " + min + " to " + max + ". Default: " + def;
        }
    }

    public static final class DoubleValue extends Value<Double> {
        private final double min;
        private final double max;

        DoubleValue(String key, double def, double min, double max, String comment) {
            super(key, def, comment);
            this.min = min;
            this.max = max;
        }

        public double getDouble() {
            return value;
        }

        public float getFloat() {
            return value.floatValue();
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        @Override
        public void set(Double newValue) {
            super.set(newValue == null || newValue.isNaN() ? def : Math.max(min, Math.min(max, newValue)));
        }

        @Override
        boolean read(JsonElement element) {
            try {
                double raw = element.getAsDouble();
                if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                    value = def;
                    return false;
                }
                double clamped = Math.max(min, Math.min(max, raw));
                value = clamped;
                return clamped == raw;
            } catch (RuntimeException e) {
                value = def;
                return false;
            }
        }

        @Override
        JsonElement toJson(Double v) {
            return new JsonPrimitive(v);
        }

        @Override
        String describeRange() {
            return "Range: " + trim(min) + " to " + trim(max) + ". Default: " + trim(def);
        }

        private static String trim(double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
    }

    public static final class StringValue extends Value<String> {
        StringValue(String key, String def, String comment) {
            super(key, def, comment);
        }

        @Override
        boolean read(JsonElement element) {
            if (element.isJsonPrimitive()) {
                value = element.getAsString();
                return element.getAsJsonPrimitive().isString();
            }
            value = def;
            return false;
        }

        @Override
        JsonElement toJson(String v) {
            return new JsonPrimitive(v);
        }

        @Override
        String describeRange() {
            return "Text. Default: \"" + def + "\"";
        }
    }

    public static final class StringListValue extends Value<List<String>> {
        StringListValue(String key, List<String> def, String comment) {
            super(key, List.copyOf(def), comment);
        }

        @Override
        public void set(List<String> newValue) {
            super.set(newValue == null ? def : List.copyOf(newValue));
        }

        @Override
        boolean read(JsonElement element) {
            if (element.isJsonArray()) {
                List<String> out = new ArrayList<>();
                boolean clean = true;
                for (JsonElement item : element.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        out.add(item.getAsString());
                    } else {
                        clean = false;
                    }
                }
                value = List.copyOf(out);
                return clean;
            }
            if (element.isJsonPrimitive()) {
                value = List.of(element.getAsString());
                return false;
            }
            value = def;
            return false;
        }

        @Override
        JsonElement toJson(List<String> v) {
            JsonArray array = new JsonArray();
            v.forEach(array::add);
            return array;
        }

        @Override
        String writeCurrent() {
            if (value.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < value.size(); i++) {
                sb.append("      ").append(GSON.toJson(value.get(i))).append(i + 1 < value.size() ? ",\n" : "\n");
            }
            return sb.append("    ]").toString();
        }

        @Override
        String describeRange() {
            return "List of text values. Default: " + GSON.toJson(toJson(def));
        }
    }

    public static final class EnumValue<E extends Enum<E>> extends Value<E> {
        private final Class<E> type;

        EnumValue(String key, E def, String comment) {
            super(key, def, comment);
            this.type = def.getDeclaringClass();
        }

        public E[] choices() {
            return type.getEnumConstants();
        }

        @Override
        boolean read(JsonElement element) {
            if (element.isJsonPrimitive()) {
                String raw = element.getAsString().trim();
                for (E constant : type.getEnumConstants()) {
                    if (constant.name().equalsIgnoreCase(raw)) {
                        value = constant;
                        return constant.name().equals(raw);
                    }
                }
            }
            value = def;
            return false;
        }

        @Override
        JsonElement toJson(E v) {
            return new JsonPrimitive(v.name());
        }

        @Override
        String describeRange() {
            StringBuilder sb = new StringBuilder("One of: ");
            E[] constants = type.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                sb.append(constants[i].name()).append(i + 1 < constants.length ? ", " : "");
            }
            return sb.append(". Default: ").append(def.name()).toString();
        }
    }
}
