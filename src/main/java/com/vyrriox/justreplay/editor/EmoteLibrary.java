package com.vyrriox.justreplay.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom emotes of the {@code justreplay/emotes} folder of the game directory, played by humanoid actors without any
 * other mod. Two JSON formats are read:
 * <ul>
 *     <li>Blockbench "Bedrock entity animation" files: every animation of a file is one emote, {@code file:animation}.
 *     Keyframes are seconds to {@code [x, y, z]} (or {@code pre}/{@code post} objects), linear, step or catmullrom;
 *     rotations in degrees, positions in pixels. Molang expressions are not evaluated (read as 0).</li>
 *     <li>Emotecraft legacy JSON emotes ({@code "emote": {..., "moves": [...]}}): one emote per file, {@code file}.
 *     Pitch, yaw, roll and x, y, z of head, torso, arms and legs are played; bend, axis and item parts are ignored.</li>
 * </ul>
 * Files are parsed once and cached; the folder is checked again (file times) when the list is asked for. A broken
 * file logs one warning and is listed as broken.
 *
 * @author vyrriox
 */
public final class EmoteLibrary {
    private static final String EXAMPLE = "example_emotes.json";

    /** Player bones, in the order of the pose arrays. */
    public enum Bone { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

    /** Channels of a bone: rotation x, y, z (radians, model space), position x, y, z (pixels, model space). */
    private static final int CHANNELS = 6;
    /** Ticks a finished emote that does not hold takes to blend back to the vanilla pose. */
    private static final double RETURN_TICKS = 4.0;

    private static final Map<String, Emote> EMOTES = new LinkedHashMap<>();
    private static final Map<String, String> BROKEN = new LinkedHashMap<>();
    private static final Map<String, Long> STAMPS = new HashMap<>();

    private EmoteLibrary() {
    }

    // ---------------------------------------------------------------- data

    private static final class Key {
        final double t;
        final double pre, post;
        /** 0 linear, 1 catmullrom, 2 step, 3+ easing ids of {@link #ease}. */
        final int mode;

        Key(double t, double pre, double post, int mode) {
            this.t = t;
            this.pre = pre;
            this.post = post;
            this.mode = mode;
        }
    }

    /** One playable emote. Times are in ticks. */
    public static final class Emote {
        public final String id;
        final Key[][][] keys = new Key[Bone.values().length][CHANNELS][];
        double length;
        boolean loop, hold;
        double loopStart;

        Emote(String id) {
            this.id = id;
        }

        public double length() {
            return length;
        }

        public boolean loops() {
            return loop;
        }
    }

    // ---------------------------------------------------------------- folder

    /** The emotes folder, created on first use with the bundled example in it when empty. */
    public static Path folder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(JustReplay.MOD_ID).resolve("emotes");
        try {
            Files.createDirectories(dir);
            boolean empty;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                empty = !stream.iterator().hasNext();
            }
            if (empty) {
                try (InputStream in = EmoteLibrary.class.getResourceAsStream("/assets/" + JustReplay.MOD_ID + "/emotes/" + EXAMPLE)) {
                    if (in != null) {
                        Files.copy(in, dir.resolve(EXAMPLE));
                    }
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot prepare the emotes folder {}: {}", dir, e.toString());
        }
        return dir;
    }

    /** Ids of every playable emote, after reloading changed files. */
    public static synchronized List<String> list() {
        refresh();
        return new ArrayList<>(EMOTES.keySet());
    }

    /** Broken files and why, after the last refresh. */
    public static synchronized Map<String, String> broken() {
        return new LinkedHashMap<>(BROKEN);
    }

    /**
     * The emote of an id ("file", "file:animation", file names with or without ".json"), or null. A file name alone
     * picks its first animation. Does not touch the disk once the folder was read.
     */
    public static synchronized Emote get(String id) {
        if (STAMPS.isEmpty()) {
            refresh();
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        Emote emote = EMOTES.get(key);
        if (emote != null) {
            return emote;
        }
        String file = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        String anim = key.contains(":") ? key.substring(key.indexOf(':') + 1) : null;
        if (file.toLowerCase(Locale.ROOT).endsWith(".json")) {
            file = file.substring(0, file.length() - 5);
        }
        for (Emote e : EMOTES.values()) {
            String eFile = e.id.contains(":") ? e.id.substring(0, e.id.indexOf(':')) : e.id;
            String eAnim = e.id.contains(":") ? e.id.substring(e.id.indexOf(':') + 1) : null;
            if (eFile.equalsIgnoreCase(file) && (anim == null || anim.equalsIgnoreCase(eAnim))) {
                return e;
            }
        }
        return null;
    }

    /** Reads new and changed files again and drops removed ones. */
    private static void refresh() {
        Path dir = folder();
        Map<String, Long> seen = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    seen.put(p.getFileName().toString(), Files.getLastModifiedTime(p).toMillis());
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot list the emotes folder {}: {}", dir, e.toString());
        }
        if (seen.equals(STAMPS) && !STAMPS.isEmpty()) {
            return;
        }
        for (String name : new ArrayList<>(STAMPS.keySet())) {
            if (!seen.containsKey(name) || !seen.get(name).equals(STAMPS.get(name))) {
                forgetFile(name);
                STAMPS.remove(name);
            }
        }
        List<String> names = new ArrayList<>(seen.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            if (STAMPS.containsKey(name)) {
                continue;
            }
            STAMPS.put(name, seen.get(name));
            try (Reader reader = Files.newBufferedReader(dir.resolve(name), StandardCharsets.UTF_8)) {
                List<Emote> parsed = parse(baseName(name), JsonParser.parseReader(reader));
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("no animation found");
                }
                for (Emote emote : parsed) {
                    EMOTES.put(emote.id, emote);
                }
            } catch (Exception e) {
                BROKEN.put(name, e.getMessage() == null ? e.toString() : e.getMessage());
                JustReplay.LOGGER.warn("Emote file {} cannot be read: {}", name, e.toString());
            }
        }
    }

    private static void forgetFile(String name) {
        BROKEN.remove(name);
        String base = baseName(name);
        EMOTES.keySet().removeIf(id -> id.equals(base) || id.startsWith(base + ":"));
    }

    private static String baseName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    // ---------------------------------------------------------------- parsing

    /** Emotes of one parsed JSON document; {@code base} is the file name without extension. */
    public static List<Emote> parse(String base, JsonElement root) {
        List<Emote> out = new ArrayList<>();
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("animations") && obj.get("animations").isJsonObject()) {
            JsonObject animations = obj.getAsJsonObject("animations");
            for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    out.add(parseBedrock(base + ":" + entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        } else if (obj.has("emote") && obj.get("emote").isJsonObject()) {
            out.add(parseEmotecraft(base, obj.getAsJsonObject("emote")));
        } else {
            throw new IllegalArgumentException("neither \"animations\" (Blockbench) nor \"emote\" (Emotecraft)");
        }
        return out;
    }

    private static Bone bone(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        return switch (n) {
            case "head" -> Bone.HEAD;
            case "body", "torso", "chest" -> Bone.BODY;
            case "rightarm", "armright" -> Bone.RIGHT_ARM;
            case "leftarm", "armleft" -> Bone.LEFT_ARM;
            case "rightleg", "legright" -> Bone.RIGHT_LEG;
            case "leftleg", "legleft" -> Bone.LEFT_LEG;
            default -> null;
        };
    }

    private static Emote parseBedrock(String id, JsonObject anim) {
        Emote emote = new Emote(id);
        JsonElement loop = anim.get("loop");
        if (loop != null && loop.isJsonPrimitive()) {
            String v = loop.getAsString();
            emote.loop = v.equalsIgnoreCase("true");
            emote.hold = v.equalsIgnoreCase("hold_on_last_frame");
        }
        double max = 0;
        if (anim.has("bones") && anim.get("bones").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : anim.getAsJsonObject("bones").entrySet()) {
                Bone bone = bone(entry.getKey());
                if (bone == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject b = entry.getValue().getAsJsonObject();
                // Bedrock bones look along +z with +y up; the Java model is turned upside down: x and y flip.
                max = Math.max(max, bedrockChannel(emote, bone, b.get("rotation"), 0, new double[]{-1, -1, 1}, true));
                max = Math.max(max, bedrockChannel(emote, bone, b.get("position"), 3, new double[]{-1, -1, 1}, false));
            }
        }
        emote.length = anim.has("animation_length") ? anim.get("animation_length").getAsDouble() * 20.0 : max;
        if (emote.length <= 0) {
            emote.length = Math.max(1, max);
        }
        return emote;
    }

    /** Reads one Bedrock vector channel; returns its last key time in ticks. */
    private static double bedrockChannel(Emote emote, Bone bone, JsonElement el, int first, double[] sign, boolean radians) {
        if (el == null || el.isJsonNull()) {
            return 0;
        }
        List<List<Key>> axes = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        double last = 0;
        if (el.isJsonObject() && !el.getAsJsonObject().has("pre") && !el.getAsJsonObject().has("post")
                && !el.getAsJsonObject().has("vector")) {
            for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
                double t;
                try {
                    t = Double.parseDouble(entry.getKey().trim()) * 20.0;
                } catch (NumberFormatException e) {
                    continue;
                }
                addBedrockKey(axes, t, entry.getValue(), sign, radians);
                last = Math.max(last, t);
            }
        } else {
            addBedrockKey(axes, 0, el, sign, radians);
        }
        for (int i = 0; i < 3; i++) {
            List<Key> keys = axes.get(i);
            keys.sort((a, b) -> Double.compare(a.t, b.t));
            emote.keys[bone.ordinal()][first + i] = keys.toArray(new Key[0]);
        }
        return last;
    }

    private static void addBedrockKey(List<List<Key>> axes, double t, JsonElement value, double[] sign, boolean radians) {
        int mode = 0;
        double[] pre;
        double[] post;
        if (value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            if (o.has("vector")) {
                pre = post = vector(o.get("vector"));
            } else {
                post = vector(o.has("post") ? o.get("post") : o.get("pre"));
                pre = vector(o.has("pre") ? o.get("pre") : o.get("post"));
            }
            if (o.has("lerp_mode")) {
                String m = o.get("lerp_mode").getAsString();
                mode = m.equalsIgnoreCase("catmullrom") ? 1 : m.equalsIgnoreCase("step") ? 2 : 0;
            }
        } else {
            pre = post = vector(value);
        }
        double unit = radians ? Mth.DEG_TO_RAD : 1.0;
        for (int i = 0; i < 3; i++) {
            axes.get(i).add(new Key(t, pre[i] * sign[i] * unit, post[i] * sign[i] * unit, mode));
        }
    }

    private static double[] vector(JsonElement el) {
        double[] v = new double[3];
        if (el == null || el.isJsonNull()) {
            return v;
        }
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            for (int i = 0; i < 3 && i < a.size(); i++) {
                v[i] = number(a.get(i));
            }
        } else if (el.isJsonPrimitive()) {
            double n = number(el);
            v[0] = v[1] = v[2] = n;
        }
        return v;
    }

    /** A number, or 0 for a Molang expression that is not a plain number. */
    private static double number(JsonElement el) {
        try {
            return el.getAsJsonPrimitive().isNumber() ? el.getAsDouble() : Double.parseDouble(el.getAsString().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Emote parseEmotecraft(String id, JsonObject e) {
        Emote emote = new Emote(id);
        int begin = e.has("beginTick") ? e.get("beginTick").getAsInt() : 0;
        int end = e.has("endTick") ? e.get("endTick").getAsInt() : 0;
        emote.loop = e.has("isLoop") && e.get("isLoop").getAsString().equalsIgnoreCase("true");
        emote.loopStart = e.has("returnTick") ? e.get("returnTick").getAsInt() : begin;
        boolean degrees = e.has("degrees") && e.get("degrees").getAsBoolean();
        Map<Integer, List<Key>> channels = new HashMap<>();
        int last = 0;
        if (e.has("moves") && e.get("moves").isJsonArray()) {
            for (JsonElement m : e.getAsJsonArray("moves")) {
                if (!m.isJsonObject()) {
                    continue;
                }
                JsonObject move = m.getAsJsonObject();
                int tick = move.has("tick") ? move.get("tick").getAsInt() : 0;
                int mode = move.has("easing") ? easing(move.get("easing").getAsString()) : 0;
                last = Math.max(last, tick);
                for (Map.Entry<String, JsonElement> part : move.entrySet()) {
                    Bone bone = bone(part.getKey());
                    if (bone == null || !part.getValue().isJsonObject()) {
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> axis : part.getValue().getAsJsonObject().entrySet()) {
                        int ch = switch (axis.getKey()) {
                            case "pitch" -> 0;
                            case "yaw" -> 1;
                            case "roll" -> 2;
                            case "x" -> 3;
                            case "y" -> 4;
                            case "z" -> 5;
                            default -> -1; // bend and axis are not played
                        };
                        if (ch < 0) {
                            continue;
                        }
                        double v = number(axis.getValue());
                        if (ch < 3 && degrees) {
                            v *= Mth.DEG_TO_RAD;
                        }
                        channels.computeIfAbsent(bone.ordinal() * CHANNELS + ch, k -> new ArrayList<>()).add(new Key(tick, v, v, mode));
                    }
                }
            }
        }
        for (Map.Entry<Integer, List<Key>> entry : channels.entrySet()) {
            List<Key> keys = entry.getValue();
            keys.sort((a, b) -> Double.compare(a.t, b.t));
            if (keys.get(0).t > begin) {
                // Emotecraft starts from the default pose.
                keys.add(0, new Key(begin, 0, 0, 0));
            }
            emote.keys[entry.getKey() / CHANNELS][entry.getKey() % CHANNELS] = keys.toArray(new Key[0]);
        }
        emote.length = Math.max(1, Math.max(end, last));
        return emote;
    }

    private static int easing(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("CONSTANT")) {
            return 2;
        }
        int shape = n.contains("SINE") ? 0 : n.contains("QUAD") ? 1 : n.contains("CUBIC") ? 2 : n.contains("QUART") ? 3 : -1;
        if (shape < 0) {
            return 0;
        }
        int kind = n.startsWith("EASEINOUT") ? 2 : n.startsWith("EASEOUT") ? 1 : n.startsWith("EASEIN") ? 0 : -1;
        return kind < 0 ? 0 : 3 + shape * 3 + kind;
    }

    private static double ease(int mode, double x) {
        int shape = (mode - 3) / 3;
        int kind = (mode - 3) % 3;
        java.util.function.DoubleUnaryOperator in = switch (shape) {
            case 0 -> v -> 1 - Math.cos(v * Math.PI / 2);
            case 1 -> v -> v * v;
            case 2 -> v -> v * v * v;
            default -> v -> v * v * v * v;
        };
        return switch (kind) {
            case 0 -> in.applyAsDouble(x);
            case 1 -> 1 - in.applyAsDouble(1 - x);
            default -> x < 0.5 ? in.applyAsDouble(x * 2) / 2 : 1 - in.applyAsDouble((1 - x) * 2) / 2;
        };
    }

    // ---------------------------------------------------------------- sampling

    /**
     * Pose of an emote {@code ticks} after its start: {@code [bone][channel]}, NaN where the emote leaves the vanilla
     * pose, plus the blend weight in {@code [6][0]} (1 while playing, falling to 0 when a finished emote returns).
     */
    public static double[][] sample(Emote emote, double ticks) {
        double[][] pose = new double[Bone.values().length + 1][CHANNELS];
        double t = Math.max(0, ticks);
        double weight = 1;
        if (emote.loop && t > emote.length) {
            double span = Math.max(1e-3, emote.length - emote.loopStart);
            t = emote.loopStart + (t - emote.loopStart) % span;
        } else if (t > emote.length) {
            if (!emote.hold) {
                weight = Math.max(0, 1 - (t - emote.length) / RETURN_TICKS);
            }
            t = emote.length;
        }
        pose[Bone.values().length][0] = weight;
        for (int b = 0; b < Bone.values().length; b++) {
            for (int c = 0; c < CHANNELS; c++) {
                Key[] keys = emote.keys[b][c];
                pose[b][c] = keys == null || keys.length == 0 ? Double.NaN : value(keys, t);
            }
        }
        return pose;
    }

    private static double value(Key[] keys, double t) {
        if (t <= keys[0].t) {
            return keys[0].pre;
        }
        int i = 0;
        while (i + 1 < keys.length && keys[i + 1].t <= t) {
            i++;
        }
        if (i + 1 >= keys.length) {
            return keys[i].post;
        }
        Key a = keys[i];
        Key b = keys[i + 1];
        double x = (t - a.t) / Math.max(1e-6, b.t - a.t);
        // Emotecraft puts the easing on the target move; Bedrock on the start key.
        int mode = a.mode == 1 || b.mode == 1 ? 1 : b.mode >= 2 ? b.mode : a.mode;
        if (mode == 2) {
            return a.post;
        }
        if (mode == 1) {
            double p0 = i > 0 ? keys[i - 1].post : a.post;
            double p3 = i + 2 < keys.length ? keys[i + 2].pre : b.pre;
            return catmull(p0, a.post, b.pre, p3, x);
        }
        if (mode >= 3) {
            x = ease(mode, x);
        }
        return a.post + (b.pre - a.post) * x;
    }

    private static double catmull(double p0, double p1, double p2, double p3, double x) {
        double x2 = x * x;
        double x3 = x2 * x;
        return 0.5 * (2 * p1 + (-p0 + p2) * x + (2 * p0 - 5 * p1 + 4 * p2 - p3) * x2 + (-p0 + 3 * p1 - 3 * p2 + p3) * x3);
    }

    // ---------------------------------------------------------------- model

    /** Rest positions of the humanoid parts, in pixels: head, body, right arm, left arm, right leg, left leg. */
    private static final float[][] REST = {{0, 0, 0}, {0, 0, 0}, {-5, 2, 0}, {5, 2, 0}, {-1.9F, 12, 0}, {1.9F, 12, 0}};

    /** Poses the model after vanilla did; rotations replace the vanilla ones, positions are offsets from rest. */
    public static void apply(HumanoidModel<?> model, double[][] pose) {
        float w = (float) pose[Bone.values().length][0];
        if (w <= 0) {
            return;
        }
        ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        for (int b = 0; b < parts.length; b++) {
            ModelPart part = parts[b];
            double[] v = pose[b];
            if (!Double.isNaN(v[0])) part.xRot = Mth.lerp(w, part.xRot, (float) v[0]);
            if (!Double.isNaN(v[1])) part.yRot = Mth.lerp(w, part.yRot, (float) v[1]);
            if (!Double.isNaN(v[2])) part.zRot = Mth.lerp(w, part.zRot, (float) v[2]);
            if (!Double.isNaN(v[3])) part.x = Mth.lerp(w, part.x, REST[b][0] + (float) v[3]);
            if (!Double.isNaN(v[4])) part.y = Mth.lerp(w, part.y, REST[b][1] + (float) v[4]);
            if (!Double.isNaN(v[5])) part.z = Mth.lerp(w, part.z, REST[b][2] + (float) v[5]);
        }
        model.hat.copyFrom(model.head);
    }
}
