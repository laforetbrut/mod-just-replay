package com.vyrriox.justreplay.editor.fx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One filter of the effect stack. Each parameter has a fixed value, or keyframes over replay ticks once at least
 * one keyframe exists. A filter is global, or bound to a camera and then applied only while that camera is live.
 *
 * @author vyrriox
 */
public final class FxEffect {
    public String id = UUID.randomUUID().toString();
    /** Name of an {@link FxType}. */
    public String type = FxType.CONTRAST.name();
    /** Id of the camera the filter is bound to, or empty for a global filter. */
    public String camera = "";
    public boolean enabled = true;
    /** Fixed value of each parameter, by parameter name. */
    public Map<String, Float> values = new LinkedHashMap<>();
    /** Keyframes of the animated parameters, by parameter name, ordered by tick. */
    public Map<String, List<Key>> keys = new LinkedHashMap<>();
    /** LUT file name in the luts folder, LUT filters only. */
    public String file = "";

    /** A keyframed value; {@link #ease} smooths the segment that starts at this key. */
    public static final class Key {
        public int tick;
        public float value;
        public boolean ease = true;

        public Key() {
        }

        public Key(int tick, float value) {
            this.tick = tick;
            this.value = value;
        }
    }

    public FxEffect() {
    }

    public FxEffect(FxType type, String camera) {
        this.type = type.name();
        this.camera = camera == null ? "" : camera;
        for (FxParam p : type.params) {
            values.put(p.name(), p.def());
        }
    }

    /** The type, or null for an unknown type read from a newer project. */
    public FxType fxType() {
        return FxType.parse(type);
    }

    public boolean isGlobal() {
        return camera == null || camera.isEmpty();
    }

    public FxEffect set(String param, float value) {
        values.put(param, value);
        return this;
    }

    /** Fixed value of a parameter (the default when unset). */
    public float base(FxParam param) {
        Float v = values.get(param.name());
        return v == null ? param.def() : v;
    }

    /** Value of a parameter at a time: interpolated between its keyframes, or its fixed value. */
    public float value(FxParam param, double time) {
        List<Key> list = keys.get(param.name());
        if (list == null || list.isEmpty()) {
            return base(param);
        }
        int n = list.size();
        Key first = list.get(0);
        if (time <= first.tick) {
            return first.value;
        }
        Key last = list.get(n - 1);
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
        Key a = list.get(lo), b = list.get(hi);
        double f = (time - a.tick) / Math.max(1, b.tick - a.tick);
        if (a.ease) {
            f = f * f * (3.0 - 2.0 * f);
        }
        return (float) (a.value + (b.value - a.value) * f);
    }

    public boolean animated(FxParam param) {
        List<Key> list = keys.get(param.name());
        return list != null && !list.isEmpty();
    }

    public Key keyAt(FxParam param, int tick) {
        List<Key> list = keys.get(param.name());
        if (list != null) {
            for (Key k : list) {
                if (k.tick == tick) {
                    return k;
                }
            }
        }
        return null;
    }

    /** Sets a keyframe at a tick, replacing one already there. */
    public Key setKey(FxParam param, int tick, float value) {
        List<Key> list = keys.computeIfAbsent(param.name(), k -> new ArrayList<>());
        Key key = keyAt(param, tick);
        if (key == null) {
            key = new Key(tick, value);
            list.add(key);
            list.sort(Comparator.comparingInt(k -> k.tick));
        }
        key.value = param.clamp(value);
        return key;
    }

    public void removeKey(FxParam param, Key key) {
        List<Key> list = keys.get(param.name());
        if (list != null) {
            list.remove(key);
            if (list.isEmpty()) {
                keys.remove(param.name());
            }
        }
    }

    /**
     * Changes a parameter as the user does: on an animated parameter the keyframe at the tick is created or updated,
     * otherwise the fixed value changes.
     */
    public void edit(FxParam param, int tick, float value) {
        if (param.keyframeable() && animated(param)) {
            setKey(param, tick, value);
        } else {
            values.put(param.name(), param.clamp(value));
        }
    }

    public FxEffect copy() {
        FxEffect c = new FxEffect();
        c.type = type;
        c.camera = camera;
        c.enabled = enabled;
        c.file = file;
        c.values.putAll(values);
        for (Map.Entry<String, List<Key>> e : keys.entrySet()) {
            List<Key> list = new ArrayList<>();
            for (Key k : e.getValue()) {
                Key nk = new Key(k.tick, k.value);
                nk.ease = k.ease;
                list.add(nk);
            }
            c.keys.put(e.getKey(), list);
        }
        return c;
    }

    /** Repairs a filter read from an older or hand edited project. */
    public void normalize() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (camera == null) camera = "";
        if (file == null) file = "";
        if (values == null) values = new LinkedHashMap<>();
        if (keys == null) keys = new LinkedHashMap<>();
        values.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        keys.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        FxType t = fxType();
        for (Map.Entry<String, List<Key>> e : keys.entrySet()) {
            e.getValue().removeIf(k -> k == null);
            FxParam p = t == null ? null : t.param(e.getKey());
            for (Key k : e.getValue()) {
                k.tick = Math.max(0, k.tick);
                if (p != null) {
                    k.value = p.clamp(k.value);
                }
            }
            e.getValue().sort(Comparator.comparingInt(k -> k.tick));
        }
        keys.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (t != null) {
            for (FxParam p : t.params) {
                values.put(p.name(), p.clamp(base(p)));
            }
        }
    }
}
