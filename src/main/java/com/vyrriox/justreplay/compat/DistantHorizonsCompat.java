package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;

/**
 * Distant Horizons during a replay. The replay world is a throw-away singleplayer world rebuilt from packets: DH must
 * not generate, build or save level-of-detail data for it, and LODs of any other world must not show around the
 * recorded chunks. While a replay runs, through DH's public API ({@code DhApi.Delayed}): LOD rendering and distant
 * generation are turned off with API overrides, and the loaded DH world is set read only so nothing reaches its
 * database. The overrides are cleared when the replay ends, which gives the player's own settings back.
 * <p>
 * Everything is reached by reflection through DH's public API interfaces: nothing here loads a DH class when DH is
 * missing.
 *
 * @author vyrriox
 */
final class DistantHorizonsCompat {
    private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";
    private static final String API = "com.seibel.distanthorizons.api.interfaces.";
    private static final String GRAPHICS = "config.client.IDhApiGraphicsConfig";
    private static final String GENERATION = "config.both.IDhApiWorldGenerationConfig";
    private static boolean overridden;
    private static boolean readOnlyApplied;
    private static int retry;

    private DistantHorizonsCompat() {
    }

    private static Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName(API + name, false, DistantHorizonsCompat.class.getClassLoader());
    }

    private static Object delayed(String field) throws Exception {
        return Class.forName(DELAYED, true, DistantHorizonsCompat.class.getClassLoader()).getField(field).get(null);
    }

    /**
     * A DH config value, {@code configs.<group>().<value>()}, or null while DH is not ready. Methods come from the
     * public API interfaces, never from DH's implementation classes.
     */
    private static Object configValue(String group, String groupType, String value) throws Exception {
        Object configs = delayed("configs");
        if (configs == null) {
            return null;
        }
        Object groupObject = api("config.IDhApiConfig").getMethod(group).invoke(configs);
        return groupObject == null ? null : api(groupType).getMethod(value).invoke(groupObject);
    }

    private static boolean override(String group, String groupType, String value, Object setting) throws Exception {
        Object config = configValue(group, groupType, value);
        if (config == null) {
            return false;
        }
        return (boolean) api("config.IDhApiConfigValue").getMethod("setValue", Object.class).invoke(config, setting);
    }

    private static void clear(String group, String groupType, String value) throws Exception {
        Object config = configValue(group, groupType, value);
        if (config != null) {
            api("config.IDhApiConfigValue").getMethod("clearValue").invoke(config);
        }
    }

    static void onReplayStart() {
        overridden = false;
        readOnlyApplied = false;
        retry = 0;
        tick();
    }

    /** Applies the overrides as soon as DH is ready, and the read only mode once DH has loaded the replay world. */
    static void tick() {
        if (overridden && readOnlyApplied) {
            return;
        }
        if (retry > 0) {
            retry--;
            return;
        }
        try {
            if (!overridden) {
                boolean rendering = override("graphics", GRAPHICS, "renderingEnabled", Boolean.FALSE);
                boolean generation = override("worldGenerator", GENERATION, "enableDistantWorldGeneration", Boolean.FALSE);
                overridden = rendering && generation;
                if (overridden) {
                    JustReplay.LOGGER.info("Distant Horizons: LOD rendering and distant generation turned off for the replay");
                }
            }
            if (!readOnlyApplied) {
                Object world = delayed("worldProxy");
                Class<?> proxy = api("world.IDhApiWorldProxy");
                if (world != null && (boolean) proxy.getMethod("worldLoaded").invoke(world)) {
                    proxy.getMethod("setReadOnly", boolean.class).invoke(world, true);
                    readOnlyApplied = true;
                    JustReplay.LOGGER.info("Distant Horizons: replay world set read only, no LOD is saved");
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.apply", "Cannot configure Distant Horizons for the replay", t);
        }
        retry = 20;
    }

    static void onReplayEnd() {
        try {
            if (overridden) {
                clear("graphics", GRAPHICS, "renderingEnabled");
                clear("worldGenerator", GENERATION, "enableDistantWorldGeneration");
                JustReplay.LOGGER.info("Distant Horizons: settings restored after the replay");
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.clear", "Cannot restore the Distant Horizons settings", t);
        }
        overridden = false;
        readOnlyApplied = false;
    }
}
