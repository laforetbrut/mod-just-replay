package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;

import java.lang.reflect.Field;

/**
 * Entity Culling switched off while a replay is open. Its culling thread raytraces from the camera position of the last
 * client tick, on wall clock time: a free camera, a camera path or an export would see entities and block entities
 * pop in late, differently on each run, and off screen entities only get a reduced tick. The mod's global switch is
 * saved on start, forced off (and again every tick, so its toggle key cannot bring it back) and restored at the end.
 * Reached by reflection; nothing here loads a class of a missing mod.
 *
 * @author vyrriox
 */
public final class EntityCullingCompat {
    private static Field enabled;
    private static boolean reflectFailed;
    private static boolean saved;
    private static boolean previous;

    private EntityCullingCompat() {
    }

    private static Field field() {
        if (enabled != null || reflectFailed || !ReplayCompat.ENTITY_CULLING) {
            return enabled;
        }
        try {
            Field f = Class.forName("dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase", false,
                    EntityCullingCompat.class.getClassLoader()).getField("enabled");
            f.getBoolean(null);
            enabled = f;
            JustReplay.LOGGER.info("Entity Culling detected: culling is switched off while a replay is open");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("entityculling.reflect", "Entity Culling support disabled, its classes changed", t);
        }
        return enabled;
    }

    /** The mod's global switch, or null when it cannot be read. */
    public static Boolean isEnabled() {
        Field f = field();
        try {
            return f == null ? null : f.getBoolean(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Test hook: the mod's toggle key, simulated. */
    public static void setEnabled(boolean value) {
        Field f = field();
        try {
            if (f != null) {
                f.setBoolean(null, value);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("entityculling.set", "Cannot change the Entity Culling switch", t);
        }
    }

    /** Test hook: how many of these entities the mod flags as culled right now, or -1 when it cannot be read. */
    public static int culledCount(Iterable<? extends Object> entities) {
        if (field() == null) {
            return -1;
        }
        try {
            ClassLoader loader = EntityCullingCompat.class.getClassLoader();
            Class<?> cullable = Class.forName("dev.tr7zw.entityculling.access.Cullable", false, loader);
            java.lang.reflect.Method isCulled = cullable.getMethod("isCulled");
            int count = 0;
            for (Object entity : entities) {
                if (cullable.isInstance(entity) && (boolean) isCulled.invoke(entity)) {
                    count++;
                }
            }
            return count;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("entityculling.count", "Cannot read the Entity Culling flags", t);
            return -1;
        }
    }

    /** Test hook: the mod's counter of entity ticks it skipped, or -1 when it cannot be read. */
    public static int skippedEntityTicks() {
        if (field() == null) {
            return -1;
        }
        try {
            Object instance = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase", false,
                    EntityCullingCompat.class.getClassLoader()).getField("instance").get(null);
            return instance == null ? -1 : instance.getClass().getField("skippedEntityTicks").getInt(instance);
        } catch (Throwable t) {
            return -1;
        }
    }

    static void onReplayStart() {
        Boolean now = isEnabled();
        if (now == null) {
            return;
        }
        if (!saved) {
            saved = true;
            previous = now;
        }
        setEnabled(false);
    }

    /** Every client tick of a running replay. */
    static void tick() {
        if (saved && Boolean.TRUE.equals(isEnabled())) {
            setEnabled(false);
        }
    }

    static void onReplayEnd() {
        if (saved) {
            saved = false;
            setEnabled(previous);
        }
    }
}
