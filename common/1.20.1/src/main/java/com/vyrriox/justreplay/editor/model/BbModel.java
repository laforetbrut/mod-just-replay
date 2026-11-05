package com.vyrriox.justreplay.editor.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A Blockbench project (.bbmodel, format 4.x) reduced to what a replay needs: cubes, bones with pivots, textures
 * and bone animations. Coordinates stay in Blockbench pixels, y up, front towards -z.
 *
 * @author vyrriox
 */
public final class BbModel {
    public static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    public final String name;
    public final List<Bone> roots = new ArrayList<>();
    /** Cubes outside any group. */
    public final List<Cube> looseCubes = new ArrayList<>();
    public final List<Texture> textures = new ArrayList<>();
    public final Map<String, Animation> animations = new LinkedHashMap<>();

    private BbModel(String name) {
        this.name = name;
    }

    public static final class Texture {
        /** PNG bytes, or null when the image could not be found. */
        public byte[] png;
        public float uvWidth = 16, uvHeight = 16;
        public String name = "";
    }

    public static final class Face {
        public float u1, v1, u2, v2;
        public int texture = -1;
        public int rotation;
    }

    public static final class Cube {
        public float[] from = new float[3], to = new float[3], origin = new float[3], rotation = new float[3];
        /** Indexed like {@link #FACES}; null for a face that is not drawn. */
        public final Face[] faces = new Face[6];
    }

    public static final class Bone {
        public String uuid = "", name = "";
        public float[] origin = new float[3], rotation = new float[3];
        public boolean isHead;
        public final List<Bone> children = new ArrayList<>();
        public final List<Cube> cubes = new ArrayList<>();
    }

    public static final class Keyframe {
        public double time;
        public String interpolation = "linear";
        public Molang.Expr[] pre, post;
    }

    public static final class Channel {
        public final List<Keyframe> keys = new ArrayList<>();

        /** Value at {@code time}, with Blockbench's step, linear and Catmull-Rom interpolation. */
        public double[] sample(double time) {
            int n = keys.size();
            if (n == 0) {
                return null;
            }
            Keyframe first = keys.get(0);
            if (n == 1 || time <= first.time) {
                return eval(first.pre, time);
            }
            Keyframe last = keys.get(n - 1);
            if (time >= last.time) {
                return eval(last.post, time);
            }
            int i = 0;
            while (i < n - 2 && time >= keys.get(i + 1).time) {
                i++;
            }
            Keyframe k1 = keys.get(i);
            Keyframe k2 = keys.get(i + 1);
            if ("step".equals(k1.interpolation)) {
                return eval(k1.post, time);
            }
            double span = k2.time - k1.time;
            double alpha = span > 0 ? (time - k1.time) / span : 0;
            double[] a = eval(k1.post, time);
            double[] b = eval(k2.pre, time);
            double[] out = new double[3];
            if ("catmullrom".equals(k1.interpolation) || "catmullrom".equals(k2.interpolation)) {
                double[] p0 = eval(keys.get(Math.max(0, i - 1)).post, time);
                double[] p3 = eval(keys.get(Math.min(n - 1, i + 2)).pre, time);
                for (int c = 0; c < 3; c++) {
                    out[c] = com.vyrriox.justreplay.editor.Interpolation.catmullRom(p0[c], a[c], b[c], p3[c], alpha);
                }
            } else {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] + (b[c] - a[c]) * alpha;
                }
            }
            return out;
        }

        private static double[] eval(Molang.Expr[] v, double time) {
            return new double[]{v[0].eval(time), v[1].eval(time), v[2].eval(time)};
        }
    }

    public static final class BoneAnimation {
        public final Channel rotation = new Channel(), position = new Channel(), scale = new Channel();
    }

    public static final class Animation {
        public String name = "";
        /** once, loop or hold. */
        public String loop = "once";
        public double length;
        public final Map<String, BoneAnimation> bones = new HashMap<>();

        /** Animation time for {@code seconds} since the start, or a negative value once a "once" animation ended. */
        public double localTime(double seconds, String loopOverride) {
            String mode = loopOverride == null || loopOverride.isBlank() ? loop : loopOverride;
            if (seconds < 0) {
                return 0;
            }
            if (length <= 0) {
                return seconds;
            }
            return switch (mode) {
                case "loop" -> seconds % length;
                case "hold" -> Math.min(seconds, length);
                default -> seconds > length ? -1 : seconds;
            };
        }
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Reads a .bbmodel file. Relative texture paths resolve against the file's folder.
     *
     * @param warn receives a message for every part that was skipped
     */
    public static BbModel read(Path file, Consumer<String> warn) throws IOException {
        String json = Files.readString(file);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String fileName = file.getFileName().toString();
        BbModel model = new BbModel(str(root, "name", fileName));
        JsonObject meta = obj(root, "meta");
        String format = meta == null ? "" : str(meta, "model_format", "");
        float shift = "java_block".equals(format) ? -8.0F : 0.0F;
        boolean metaBoxUv = meta != null && bool(meta, "box_uv", false);

        float resW = 16, resH = 16;
        JsonObject resolution = obj(root, "resolution");
        if (resolution != null) {
            resW = num(resolution, "width", 16);
            resH = num(resolution, "height", 16);
        }

        // Textures
        Map<String, Integer> textureByUuid = new HashMap<>();
        JsonArray textures = arr(root, "textures");
        if (textures != null) {
            for (JsonElement e : textures) {
                Texture texture = new Texture();
                texture.uvWidth = resW;
                texture.uvHeight = resH;
                if (e.isJsonObject()) {
                    JsonObject t = e.getAsJsonObject();
                    texture.name = str(t, "name", "");
                    texture.uvWidth = num(t, "uv_width", resW);
                    texture.uvHeight = num(t, "uv_height", resH);
                    texture.png = readTexture(t, file.getParent(), warn);
                    textureByUuid.put(str(t, "uuid", "#" + model.textures.size()), model.textures.size());
                }
                model.textures.add(texture);
            }
        }

        // Elements
        Map<String, Cube> cubes = new LinkedHashMap<>();
        JsonArray elements = arr(root, "elements");
        int skipped = 0;
        if (elements != null) {
            for (JsonElement e : elements) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject el = e.getAsJsonObject();
                String type = str(el, "type", "cube");
                if (!"cube".equals(type)) {
                    skipped++;
                    continue;
                }
                if (!bool(el, "visibility", true) || !bool(el, "export", true)) {
                    continue;
                }
                Cube cube = parseCube(el, shift, metaBoxUv || bool(el, "box_uv", false), textureByUuid);
                cubes.put(str(el, "uuid", "#" + cubes.size()), cube);
            }
        }
        if (skipped > 0) {
            warn.accept(skipped + " mesh or non cube element(s) are not supported and were skipped");
        }

        // Groups: inline in the outliner (4.x) or listed in "groups" and referenced by uuid (newer files).
        Map<String, JsonObject> groups = new HashMap<>();
        JsonArray groupList = arr(root, "groups");
        if (groupList != null) {
            for (JsonElement e : groupList) {
                if (e.isJsonObject()) {
                    groups.put(str(e.getAsJsonObject(), "uuid", ""), e.getAsJsonObject());
                }
            }
        }
        java.util.Set<Cube> placed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        JsonArray outliner = arr(root, "outliner");
        if (outliner != null) {
            for (JsonElement e : outliner) {
                if (e.isJsonPrimitive()) {
                    Cube cube = cubes.get(e.getAsString());
                    if (cube != null && placed.add(cube)) {
                        model.looseCubes.add(cube);
                    }
                } else if (e.isJsonObject()) {
                    Bone bone = parseBone(e.getAsJsonObject(), groups, cubes, placed, shift, 0);
                    if (bone != null) {
                        model.roots.add(bone);
                    }
                }
            }
        }
        if (outliner == null) {
            for (Cube cube : cubes.values()) {
                model.looseCubes.add(cube);
            }
        }

        // Animations
        JsonArray animations = arr(root, "animations");
        if (animations != null) {
            int badValues = 0;
            for (JsonElement e : animations) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject a = e.getAsJsonObject();
                Animation animation = new Animation();
                animation.name = str(a, "name", "animation" + model.animations.size());
                animation.loop = str(a, "loop", "once");
                animation.length = num(a, "length", 0);
                JsonObject animators = obj(a, "animators");
                if (animators != null) {
                    for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                        if (!entry.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject animator = entry.getValue().getAsJsonObject();
                        String type = str(animator, "type", "bone");
                        if (!"bone".equals(type)) {
                            continue;
                        }
                        BoneAnimation boneAnimation = new BoneAnimation();
                        JsonArray keyframes = arr(animator, "keyframes");
                        if (keyframes == null) {
                            continue;
                        }
                        for (JsonElement k : keyframes) {
                            if (!k.isJsonObject()) {
                                continue;
                            }
                            JsonObject key = k.getAsJsonObject();
                            Channel channel = switch (str(key, "channel", "")) {
                                case "rotation" -> boneAnimation.rotation;
                                case "position" -> boneAnimation.position;
                                case "scale" -> boneAnimation.scale;
                                default -> null;
                            };
                            JsonArray points = arr(key, "data_points");
                            if (channel == null || points == null || points.isEmpty()) {
                                continue;
                            }
                            Keyframe keyframe = new Keyframe();
                            keyframe.time = num(key, "time", 0);
                            keyframe.interpolation = str(key, "interpolation", "linear");
                            double fallback = channel == boneAnimation.scale ? 1.0 : 0.0;
                            int[] bad = new int[1];
                            keyframe.pre = vector(points.get(0), fallback, bad);
                            keyframe.post = points.size() > 1 ? vector(points.get(1), fallback, bad) : keyframe.pre;
                            badValues += bad[0];
                            channel.keys.add(keyframe);
                        }
                        boneAnimation.rotation.keys.sort(java.util.Comparator.comparingDouble(k -> k.time));
                        boneAnimation.position.keys.sort(java.util.Comparator.comparingDouble(k -> k.time));
                        boneAnimation.scale.keys.sort(java.util.Comparator.comparingDouble(k -> k.time));
                        boneAnimation(animation, entry.getKey(), boneAnimation);
                    }
                }
                model.animations.put(animation.name, animation);
            }
            if (badValues > 0) {
                warn.accept(badValues + " keyframe value(s) use unsupported expressions and play as 0");
            }
        }
        return model;
    }

    private static void boneAnimation(Animation animation, String uuid, BoneAnimation boneAnimation) {
        animation.bones.put(uuid, boneAnimation);
    }

    public Animation animation(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Animation exact = animations.get(name);
        if (exact != null) {
            return exact;
        }
        for (Animation animation : animations.values()) {
            if (animation.name.equalsIgnoreCase(name.trim())) {
                return animation;
            }
        }
        return null;
    }

    private static Bone parseBone(JsonObject g, Map<String, JsonObject> groups, Map<String, Cube> cubes, java.util.Set<Cube> placed,
                                  float shift, int depth) {
        if (depth > 64) {
            return null;
        }
        Bone bone = new Bone();
        bone.uuid = str(g, "uuid", "");
        JsonObject data = groups.getOrDefault(bone.uuid, g);
        if (!bool(data, "visibility", true) || !bool(data, "export", true)) {
            return null;
        }
        bone.name = str(data, "name", str(g, "name", ""));
        bone.isHead = bone.name.toLowerCase(Locale.ROOT).equals("head");
        bone.origin = vec3(data, "origin", 0);
        bone.origin[0] += shift;
        bone.origin[2] += shift;
        bone.rotation = vec3(data, "rotation", 0);
        JsonArray children = arr(g, "children");
        if (children != null) {
            for (JsonElement c : children) {
                if (c.isJsonPrimitive()) {
                    Cube cube = cubes.get(c.getAsString());
                    if (cube != null && placed.add(cube)) {
                        bone.cubes.add(cube);
                    }
                } else if (c.isJsonObject()) {
                    Bone child = parseBone(c.getAsJsonObject(), groups, cubes, placed, shift, depth + 1);
                    if (child != null) {
                        bone.children.add(child);
                    }
                }
            }
        }
        return bone;
    }

    private static Cube parseCube(JsonObject el, float shift, boolean boxUv, Map<String, Integer> textureByUuid) {
        Cube cube = new Cube();
        cube.from = vec3(el, "from", 0);
        cube.to = vec3(el, "to", 0);
        cube.origin = vec3(el, "origin", 0);
        float inflate = num(el, "inflate", 0);
        for (int i = 0; i < 3; i++) {
            float lo = Math.min(cube.from[i], cube.to[i]) - inflate;
            float hi = Math.max(cube.from[i], cube.to[i]) + inflate;
            cube.from[i] = lo;
            cube.to[i] = hi;
        }
        cube.from[0] += shift;
        cube.to[0] += shift;
        cube.from[2] += shift;
        cube.to[2] += shift;
        cube.origin[0] += shift;
        cube.origin[2] += shift;
        JsonElement rotation = el.get("rotation");
        if (rotation != null && rotation.isJsonArray()) {
            cube.rotation = vec3(el, "rotation", 0);
        } else if (rotation != null && rotation.isJsonObject()) {
            // Older files: one angle around one axis, with its own origin.
            JsonObject r = rotation.getAsJsonObject();
            float angle = num(r, "angle", 0);
            int axis = switch (str(r, "axis", "y")) {
                case "x" -> 0;
                case "z" -> 2;
                default -> 1;
            };
            cube.rotation[axis] = angle;
            if (r.has("origin")) {
                cube.origin = vec3(r, "origin", 0);
                cube.origin[0] += shift;
                cube.origin[2] += shift;
            }
        }
        JsonObject faces = obj(el, "faces");
        float[] size = {cube.to[0] - cube.from[0], cube.to[1] - cube.from[1], cube.to[2] - cube.from[2]};
        float[] uvOffset = vec3or2(el, "uv_offset");
        for (int i = 0; i < FACES.length; i++) {
            JsonObject f = faces == null ? null : obj(faces, FACES[i]);
            if (f == null) {
                continue;
            }
            JsonElement texture = f.get("texture");
            int textureIndex = -1;
            if (texture != null && texture.isJsonPrimitive()) {
                if (texture.getAsJsonPrimitive().isNumber()) {
                    textureIndex = texture.getAsInt();
                } else if (texture.getAsJsonPrimitive().isString()) {
                    textureIndex = textureByUuid.getOrDefault(texture.getAsString(), -1);
                }
            }
            if (textureIndex < 0) {
                continue;
            }
            Face face = new Face();
            face.texture = textureIndex;
            face.rotation = Math.floorMod(Math.round(num(f, "rotation", 0) / 90.0F), 4);
            JsonArray uv = arr(f, "uv");
            if (uv != null && uv.size() >= 4) {
                face.u1 = uv.get(0).getAsFloat();
                face.v1 = uv.get(1).getAsFloat();
                face.u2 = uv.get(2).getAsFloat();
                face.v2 = uv.get(3).getAsFloat();
            } else if (boxUv) {
                float[] box = boxUv(FACES[i], size, uvOffset);
                face.u1 = box[0];
                face.v1 = box[1];
                face.u2 = box[2];
                face.v2 = box[3];
            } else {
                continue;
            }
            cube.faces[i] = face;
        }
        return cube;
    }

    /** Blockbench box UV layout for a face, used when a file saved no per face UV. */
    private static float[] boxUv(String face, float[] size, float[] offset) {
        float x = size[0], y = size[1], z = size[2];
        float u = offset[0], v = offset[1];
        return switch (face) {
            case "east" -> new float[]{u, v + z, u + z, v + z + y};
            case "north" -> new float[]{u + z, v + z, u + z + x, v + z + y};
            case "west" -> new float[]{u + z + x, v + z, u + z + x + z, v + z + y};
            case "south" -> new float[]{u + 2 * z + x, v + z, u + 2 * z + 2 * x, v + z + y};
            case "up" -> new float[]{u + z + x, v + z, u + z, v};
            default -> new float[]{u + z + 2 * x, v, u + z + x, v + z};
        };
    }

    private static byte[] readTexture(JsonObject t, Path folder, Consumer<String> warn) {
        String source = str(t, "source", "");
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma > 0 && source.substring(0, comma).contains("base64")) {
                try {
                    return Base64.getDecoder().decode(source.substring(comma + 1).trim());
                } catch (IllegalArgumentException e) {
                    warn.accept("texture " + str(t, "name", "?") + " has invalid base64 data");
                    return null;
                }
            }
        }
        List<Path> candidates = new ArrayList<>();
        String relative = str(t, "relative_path", "");
        if (!relative.isEmpty() && folder != null) {
            candidates.add(folder.resolve(relative));
        }
        String name = str(t, "name", "");
        if (!name.isEmpty() && folder != null) {
            candidates.add(folder.resolve(name));
        }
        String absolute = str(t, "path", "");
        if (!absolute.isEmpty()) {
            candidates.add(Path.of(absolute));
        }
        for (Path candidate : candidates) {
            try {
                Path normal = candidate.normalize();
                if (normal.getFileName() != null && normal.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
                        && Files.isRegularFile(normal)) {
                    return Files.readAllBytes(normal);
                }
            } catch (Exception ignored) {
                // An invalid path in the file: try the next candidate.
            }
        }
        warn.accept("texture " + (name.isEmpty() ? "?" : name) + " is neither embedded nor found beside the model");
        return null;
    }

    private static Molang.Expr[] vector(JsonElement point, double fallback, int[] bad) {
        Molang.Expr[] out = new Molang.Expr[3];
        JsonObject p = point.isJsonObject() ? point.getAsJsonObject() : new JsonObject();
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            JsonElement v = p.get(axes[i]);
            if (v == null || v.isJsonNull()) {
                out[i] = t -> fallback;
                continue;
            }
            try {
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double d = v.getAsDouble();
                    out[i] = t -> d;
                } else {
                    String text = v.getAsString();
                    out[i] = text.isBlank() ? t -> fallback : Molang.parse(text);
                }
            } catch (RuntimeException e) {
                bad[0]++;
                out[i] = t -> fallback;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- json helpers

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static float num(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsFloat() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static float[] vec3(JsonObject o, String key, float fallback) {
        float[] out = {fallback, fallback, fallback};
        JsonArray a = arr(o, key);
        if (a != null) {
            for (int i = 0; i < Math.min(3, a.size()); i++) {
                try {
                    out[i] = a.get(i).getAsFloat();
                } catch (RuntimeException ignored) {
                    // Keep the fallback for a value that is not a number.
                }
            }
        }
        return out;
    }

    private static float[] vec3or2(JsonObject o, String key) {
        return vec3(o, key, 0);
    }
}
