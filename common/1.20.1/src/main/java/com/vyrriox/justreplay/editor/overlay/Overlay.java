package com.vyrriox.justreplay.editor.overlay;

import com.vyrriox.justreplay.editor.fx.FxEffect;
import com.vyrriox.justreplay.editor.fx.FxParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A text or an image living in the world of the replay, like a title or a sticker of a video editor. It hangs
 * from an anchor (a fixed position, a block face, or an entity it follows), faces the camera or keeps its own
 * orientation, shows between two ticks with optional fades, and animates its offset, scale, rotation and opacity
 * with keyframes like the effect parameters.
 *
 * @author vyrriox
 */
public final class Overlay {
    public static final String TEXT = "TEXT";
    public static final String IMAGE = "IMAGE";

    /** Anchored to {@link #x}, {@link #y}, {@link #z}. */
    public static final String POSITION = "POSITION";
    /** Anchored to the {@link #face} of the block at {@link #blockX}, {@link #blockY}, {@link #blockZ}. */
    public static final String BLOCK = "BLOCK";
    /** Anchored above the head of the entity named by {@link #target}. */
    public static final String ENTITY = "ENTITY";

    /** Always turned towards the camera. */
    public static final String BILLBOARD = "BILLBOARD";
    /** Fixed yaw, pitch and roll. */
    public static final String FIXED = "FIXED";
    /** Lying on the block face it is anchored to. */
    public static final String FLAT = "FLAT";

    /** {@link #target} of an overlay following the recording player. */
    public static final String RECORDER = "recorder";
    /** Prefix of a {@link #target} following an actor, followed by the actor id. */
    public static final String ACTOR_PREFIX = "actor:";

    public static final String LEFT = "LEFT";
    public static final String CENTER = "CENTER";
    public static final String RIGHT = "RIGHT";

    // Animatable parameters, keyframed like the effect parameters.
    public static final FxParam OFFSET_X = FxParam.of("offset_x", -8.0F, 8.0F, 0.0F, 0.05F);
    public static final FxParam OFFSET_Y = FxParam.of("offset_y", -8.0F, 8.0F, 0.0F, 0.05F);
    public static final FxParam OFFSET_Z = FxParam.of("offset_z", -8.0F, 8.0F, 0.0F, 0.05F);
    public static final FxParam SCALE = FxParam.of("scale", 0.05F, 8.0F, 1.0F, 0.05F);
    public static final FxParam YAW = FxParam.of("yaw", -180.0F, 180.0F, 0.0F, 1.0F);
    public static final FxParam PITCH = FxParam.of("pitch", -90.0F, 90.0F, 0.0F, 1.0F);
    public static final FxParam ROLL = FxParam.of("roll", -180.0F, 180.0F, 0.0F, 1.0F);
    public static final FxParam OPACITY = FxParam.of("opacity", 0.0F, 1.0F, 1.0F, 0.01F);
    public static final List<FxParam> PARAMS = List.of(OFFSET_X, OFFSET_Y, OFFSET_Z, SCALE, YAW, PITCH, ROLL, OPACITY);

    public String id = UUID.randomUUID().toString();
    public String name = "";
    /** {@link #TEXT} or {@link #IMAGE}. */
    public String type = TEXT;
    public boolean visible = true;

    // Anchor
    /** {@link #POSITION}, {@link #BLOCK} or {@link #ENTITY}. */
    public String anchor = POSITION;
    public double x, y, z;
    public int blockX, blockY, blockZ;
    /** Name of the block face ({@code UP}, {@code NORTH}...). */
    public String face = "UP";
    /** {@link #RECORDER}, {@link #ACTOR_PREFIX} + actor id, or the UUID of a recorded entity. */
    public String target = "";
    /** Name shown in the editor for the followed entity. */
    public String targetName = "";
    /** {@link #BILLBOARD}, {@link #FIXED} or {@link #FLAT}. */
    public String facing = BILLBOARD;
    /** Draw over the world instead of being hidden by blocks in front. */
    public boolean throughWalls;

    // Timing
    /** First tick the overlay shows. */
    public int start;
    /** Last tick the overlay shows. */
    public int end = 100;
    public int fadeIn = 10;
    public int fadeOut = 10;

    /** Fixed value of each animatable parameter, by parameter name. */
    public Map<String, Float> values = new LinkedHashMap<>();
    /** Keyframes of the animated parameters, by parameter name, ordered by tick. */
    public Map<String, List<FxEffect.Key>> keys = new LinkedHashMap<>();

    // Text
    /** Content; lines are split on line breaks or {@code \n}, {@code &} starts a Minecraft formatting code. */
    public String text = "Text";
    /** Height of one line of text, in blocks. */
    public float fontSize = 0.25F;
    /** RGB colour of the text where no formatting code sets one. */
    public int color = 0xFFFFFF;
    /** {@link #LEFT}, {@link #CENTER} or {@link #RIGHT}. */
    public String align = CENTER;
    public boolean shadow = true;
    public boolean outline;
    public int outlineColor = 0x000000;
    /** Rounded background plate behind the text. */
    public boolean plate = true;
    public int plateColor = 0x0B0F16;
    public float plateOpacity = 0.55F;
    /** Space between the text and the edge of the plate, in text pixels. */
    public float padding = 3.0F;

    // Image
    /** File name in the images folder. */
    public String image = "";
    /** Width in blocks. */
    public float width = 2.0F;
    /** Height in blocks, used when {@link #keepAspect} is off. */
    public float height = 2.0F;
    public boolean keepAspect = true;
    /** RGB colour multiplied with the image. */
    public int tint = 0xFFFFFF;

    public Overlay() {
    }

    public Overlay(String type) {
        this.type = IMAGE.equals(type) ? IMAGE : TEXT;
    }

    public boolean isText() {
        return !IMAGE.equals(type);
    }

    public boolean isImage() {
        return IMAGE.equals(type);
    }

    public boolean isBillboard() {
        return BILLBOARD.equals(facing);
    }

    /** Name shown in the editor: the given name, else the first line of the text or the image file. */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (isImage()) {
            return image == null || image.isBlank() ? "Image" : image;
        }
        List<String> lines = lines();
        String first = lines.isEmpty() ? "" : net.minecraft.ChatFormatting.stripFormatting(lines.get(0));
        return first == null || first.isBlank() ? "Text" : first;
    }

    /** Lines of the text, with {@code &} codes turned into Minecraft formatting codes. */
    public List<String> lines() {
        String content = text == null ? "" : text.replace("\\n", "\n").replace("\r", "");
        List<String> out = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            out.add(formatCodes(line));
        }
        return out;
    }

    /** {@code &6} becomes {@code §6}; {@code &&} stays a single {@code &}. */
    static String formatCodes(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '&' && i + 1 < line.length()) {
                char n = Character.toLowerCase(line.charAt(i + 1));
                if (n == '&') {
                    sb.append('&');
                    i++;
                    continue;
                }
                if ((n >= '0' && n <= '9') || (n >= 'a' && n <= 'f') || (n >= 'k' && n <= 'o') || n == 'r') {
                    sb.append('§').append(n);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- timing

    /** True when the overlay shows at a time (fades aside). */
    public boolean visibleAt(double time) {
        return visible && time >= start && time <= end;
    }

    /** Fade factor at a time, 0 outside the visible range. */
    public float fade(double time) {
        if (time < start || time > end) {
            return 0.0F;
        }
        double a = 1.0;
        if (fadeIn > 0) {
            a = Math.min(a, (time - start) / fadeIn);
        }
        if (fadeOut > 0) {
            a = Math.min(a, (end - time) / fadeOut);
        }
        return (float) Math.max(0.0, Math.min(1.0, a));
    }

    /** Moves the visible range (and the keyframes with it) by a number of ticks. */
    public void shift(int delta) {
        start += delta;
        end += delta;
        for (List<FxEffect.Key> list : keys.values()) {
            for (FxEffect.Key k : list) {
                k.tick = Math.max(0, k.tick + delta);
            }
        }
    }

    // ---------------------------------------------------------------- animated parameters

    /** Fixed value of a parameter (the default when unset). */
    public float base(FxParam param) {
        Float v = values.get(param.name());
        return v == null ? param.def() : v;
    }

    public void setBase(FxParam param, float value) {
        values.put(param.name(), param.clamp(value));
    }

    /** Value of a parameter at a time: interpolated between its keyframes, or its fixed value. */
    public float value(FxParam param, double time) {
        List<FxEffect.Key> list = keys.get(param.name());
        if (list == null || list.isEmpty()) {
            return base(param);
        }
        int n = list.size();
        FxEffect.Key first = list.get(0);
        if (time <= first.tick) {
            return first.value;
        }
        FxEffect.Key last = list.get(n - 1);
        if (time >= last.tick) {
            return last.value;
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).tick <= time) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        FxEffect.Key a = list.get(lo), b = list.get(hi);
        double f = (time - a.tick) / Math.max(1, b.tick - a.tick);
        if (a.ease) {
            f = f * f * (3.0 - 2.0 * f);
        }
        return (float) (a.value + (b.value - a.value) * f);
    }

    public boolean animated(FxParam param) {
        List<FxEffect.Key> list = keys.get(param.name());
        return list != null && !list.isEmpty();
    }

    public FxEffect.Key keyAt(FxParam param, int tick) {
        List<FxEffect.Key> list = keys.get(param.name());
        if (list != null) {
            for (FxEffect.Key k : list) {
                if (k.tick == tick) {
                    return k;
                }
            }
        }
        return null;
    }

    /** Sets a keyframe at a tick, replacing one already there. */
    public FxEffect.Key setKey(FxParam param, int tick, float value) {
        List<FxEffect.Key> list = keys.computeIfAbsent(param.name(), k -> new ArrayList<>());
        FxEffect.Key key = keyAt(param, tick);
        if (key == null) {
            key = new FxEffect.Key(tick, value);
            list.add(key);
            list.sort(Comparator.comparingInt(k -> k.tick));
        }
        key.value = param.clamp(value);
        return key;
    }

    public void removeKey(FxParam param, FxEffect.Key key) {
        List<FxEffect.Key> list = keys.get(param.name());
        if (list != null) {
            list.remove(key);
            if (list.isEmpty()) {
                keys.remove(param.name());
            }
        }
    }

    /** Changes a parameter as the user does: the keyframe at the tick once animated, else the fixed value. */
    public void edit(FxParam param, int tick, float value) {
        if (animated(param)) {
            setKey(param, tick, value);
        } else {
            setBase(param, value);
        }
    }

    /** Number of keyframes over every parameter. */
    public int keyCount() {
        int n = 0;
        for (List<FxEffect.Key> list : keys.values()) {
            n += list.size();
        }
        return n;
    }

    // ---------------------------------------------------------------- copies

    /** Deep copy with a new id. */
    public Overlay copy() {
        Overlay c = new Overlay(type);
        c.name = name;
        c.visible = visible;
        c.copyAnchor(this);
        c.throughWalls = throughWalls;
        c.start = start;
        c.end = end;
        c.fadeIn = fadeIn;
        c.fadeOut = fadeOut;
        for (Map.Entry<String, List<FxEffect.Key>> e : keys.entrySet()) {
            List<FxEffect.Key> list = new ArrayList<>();
            for (FxEffect.Key k : e.getValue()) {
                FxEffect.Key nk = new FxEffect.Key(k.tick, k.value);
                nk.ease = k.ease;
                list.add(nk);
            }
            c.keys.put(e.getKey(), list);
        }
        c.text = text;
        c.fontSize = fontSize;
        c.color = color;
        c.align = align;
        c.shadow = shadow;
        c.outline = outline;
        c.outlineColor = outlineColor;
        c.plate = plate;
        c.plateColor = plateColor;
        c.plateOpacity = plateOpacity;
        c.padding = padding;
        c.image = image;
        c.width = width;
        c.height = height;
        c.keepAspect = keepAspect;
        c.tint = tint;
        return c;
    }

    /** Takes the anchor, facing and fixed parameter values of another overlay (a placement and its undo). */
    public void copyAnchor(Overlay other) {
        anchor = other.anchor;
        x = other.x;
        y = other.y;
        z = other.z;
        blockX = other.blockX;
        blockY = other.blockY;
        blockZ = other.blockZ;
        face = other.face;
        target = other.target;
        targetName = other.targetName;
        facing = other.facing;
        values = new LinkedHashMap<>(other.values);
    }

    /** Repairs an overlay read from an older or hand edited project. */
    public void normalize() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null) name = "";
        type = IMAGE.equals(type) ? IMAGE : TEXT;
        if (!BLOCK.equals(anchor) && !ENTITY.equals(anchor)) anchor = POSITION;
        if (!FIXED.equals(facing) && !FLAT.equals(facing)) facing = BILLBOARD;
        if (FLAT.equals(facing) && !BLOCK.equals(anchor)) facing = FIXED;
        face = parseFace(face).toUpperCase(Locale.ROOT);
        if (target == null) target = "";
        if (targetName == null) targetName = "";
        if (!Double.isFinite(x)) x = 0;
        if (!Double.isFinite(y)) y = 0;
        if (!Double.isFinite(z)) z = 0;
        start = Math.max(0, start);
        end = Math.max(start + 1, end);
        fadeIn = Math.max(0, Math.min(1200, fadeIn));
        fadeOut = Math.max(0, Math.min(1200, fadeOut));
        if (values == null) values = new LinkedHashMap<>();
        if (keys == null) keys = new LinkedHashMap<>();
        values.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        keys.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        for (FxParam p : PARAMS) {
            if (values.containsKey(p.name())) {
                values.put(p.name(), p.clamp(values.get(p.name())));
            }
            List<FxEffect.Key> list = keys.get(p.name());
            if (list != null) {
                list.removeIf(k -> k == null);
                for (FxEffect.Key k : list) {
                    k.tick = Math.max(0, k.tick);
                    k.value = p.clamp(k.value);
                }
                list.sort(Comparator.comparingInt(k -> k.tick));
            }
        }
        keys.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (text == null) text = "";
        if (!(fontSize >= 0.01F && fontSize <= 16.0F)) fontSize = 0.25F;
        if (!LEFT.equals(align) && !RIGHT.equals(align)) align = CENTER;
        if (!(plateOpacity >= 0.0F && plateOpacity <= 1.0F)) plateOpacity = 0.55F;
        if (!(padding >= 0.0F && padding <= 32.0F)) padding = 3.0F;
        if (image == null) image = "";
        if (!(width >= 0.05F && width <= 256.0F)) width = 2.0F;
        if (!(height >= 0.05F && height <= 256.0F)) height = 2.0F;
        color &= 0xFFFFFF;
        outlineColor &= 0xFFFFFF;
        plateColor &= 0xFFFFFF;
        tint &= 0xFFFFFF;
    }

    private static String parseFace(String value) {
        if (value != null) {
            for (String f : new String[]{"down", "up", "north", "south", "west", "east"}) {
                if (f.equalsIgnoreCase(value.trim())) {
                    return f;
                }
            }
        }
        return "up";
    }
}
