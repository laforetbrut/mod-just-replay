package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.platform.EarlyMods;
import com.vyrriox.justreplay.platform.Platform;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The state of every integration of this Minecraft version, decided once from the mod list, {@link CompatTargets} and
 * the {@code compat} section of the config. No game class: the voice chat plugin reaches it on a dedicated server
 * too, and {@link ReplayCompat} (client only) forwards its hooks through these objects.
 *
 * @author vyrriox
 */
public final class Integrations {
    /** Mod ids found installed, for the startup line and the settings screen. Filled by {@link #detectAll()}. */
    private static final Set<String> FOUND = new HashSet<>();
    /** Mod id of the integration the self test turns off at startup ({@link Integration#FAIL_PROPERTY}), or null. */
    private static final String FORCED_FAILURE = Integration.forcedFailureId(System.getProperty(Integration.FAIL_PROPERTY));
    /** Warnings the integration forced off has written: the self test expects one, however often its hooks run. */
    private static final AtomicInteger FORCED_WARNINGS = new AtomicInteger();
    /** Every integration by mod id, in the order of {@link CompatTargets#NAMES}. */
    private static final Map<String, Integration> ALL = detectAll();

    public static final Integration ARMOURERS_WORKSHOP = ALL.get("armourers_workshop");
    public static final Integration BOBBY = ALL.get("bobby");
    public static final Integration CURIOS = ALL.get("curios");
    public static final Integration CUSTOM_NPCS = ALL.get("customnpcs");
    public static final Integration DISTANT_HORIZONS = ALL.get("distanthorizons");
    public static final Integration ELYTRA_SLOT = ALL.get("elytraslot");
    public static final Integration ENTITY_CULLING = ALL.get("entityculling");
    public static final Integration EPIC_FIGHT = ALL.get("epicfight");
    public static final Integration GECKOLIB = ALL.get("geckolib");
    public static final Integration IMMERSIVE_VEHICLES = ALL.get("mts");
    public static final Integration IRIS = ALL.get("iris");
    public static final Integration JADE = ALL.get("jade");
    public static final Integration MORE_PLAYER_MODELS = ALL.get("moreplayermodels");
    public static final Integration PLAYER_ANIMATOR = ALL.get(CompatTargets.PLAYER_ANIMATOR);
    public static final Integration VOICE_CHAT = ALL.get(CompatTargets.VOICE_CHAT);
    public static final Integration WATERFRAMES = ALL.get("waterframes");

    private Integrations() {
    }

    private static Map<String, Integration> detectAll() {
        Map<String, Integration> all = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : CompatTargets.NAMES.entrySet()) {
            all.put(entry.getKey(), detect(entry.getKey(), entry.getValue()));
        }
        if (FORCED_FAILURE != null && !all.containsKey(FORCED_FAILURE)) {
            LoggerFactory.getLogger(Integrations.class).warn("-D{}={}: Just Replay has no integration with that mod on this "
                    + "Minecraft version, nothing was turned off", Integration.FAIL_PROPERTY, FORCED_FAILURE);
        }
        return Collections.unmodifiableMap(all);
    }

    private static Integration detect(String modId, String name) {
        boolean installed = false;
        boolean supported = false;
        boolean enabled = true;
        try {
            installed = isInstalled(modId);
            if (installed) {
                FOUND.add(modId);
            }
            supported = CompatTargets.isSupported(modId);
            enabled = CompatSwitches.isEnabled(modId);
            // Mixin integrations whose library may not be found by its mod id stay armed: their mixins only act on it.
            installed |= CompatTargets.isPresenceUnknown(modId);
        } catch (Throwable t) {
            LoggerFactory.getLogger(Integrations.class).warn("Cannot tell whether the {} integration may run, it stays off: {}",
                    name, t.toString());
            installed = false;
        }
        if (!modId.equals(FORCED_FAILURE)) {
            return Integration.of(modId, name, installed, supported, enabled);
        }
        // Self test: counted as installed even when it is not, so that the startup line shows it failed. It fails
        // before any of its hooks runs, through the same guard as they do.
        FOUND.add(modId);
        Integration integration = Integration.counted(modId, name, true, supported, enabled, FORCED_WARNINGS);
        integration.run(Integration.FORCED_HOOK, () -> {
            throw new IllegalStateException("forced by -D" + Integration.FAIL_PROPERTY + "=" + modId);
        });
        return integration;
    }

    private static boolean isInstalled(String modId) {
        for (String id : CompatTargets.modIds(modId)) {
            if (EarlyMods.isLoaded(id)) {
                return true;
            }
            try {
                if (Platform.get().isModLoaded(id)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // The loader has no mod list yet: the early list answered.
            }
        }
        return false;
    }

    /** Every integration of this Minecraft version, in the order of the settings. */
    public static List<Integration> all() {
        return List.copyOf(ALL.values());
    }

    /** The integration with this mod, or null when this Minecraft version has none. */
    public static Integration get(String modId) {
        return ALL.get(modId);
    }

    /** True when this integration's mod was found installed (the settings screen shows its switch). */
    public static boolean isFound(Integration integration) {
        return FOUND.contains(integration.modId());
    }

    /** The integration the self test turned off at startup, or null (see {@link Integration#FAIL_PROPERTY}). */
    public static Integration forcedFailure() {
        return FORCED_FAILURE == null ? null : ALL.get(FORCED_FAILURE);
    }

    /** The mod id {@link Integration#FAIL_PROPERTY} names, or null. */
    public static String forcedFailureId() {
        return FORCED_FAILURE;
    }

    /** Warnings the integration forced off has written so far. */
    public static int forcedFailureWarnings() {
        return FORCED_WARNINGS.get();
    }

    /**
     * Writes one line at startup with this mod's version, the loader, the game and the state of each integration
     * found, so that a crash log tells at once what Just Replay was doing.
     */
    public static void logStartup() {
        try {
            LoggerFactory.getLogger("com.vyrriox.justreplay.JustReplay").info(startupLine());
        } catch (Throwable t) {
            LoggerFactory.getLogger(Integrations.class).warn("Cannot describe the Just Replay integrations: {}", t.toString());
        }
    }

    public static String startupLine() {
        Platform platform = Platform.get();
        String loader = platform.loaderName();
        String loaderId = "fabric".equals(loader) ? "fabricloader" : loader;
        String loaderVersion = "";
        String forgeVersion = "";
        String game = "";
        for (String mod : platform.modList()) {
            int at = mod.indexOf('@');
            String id = at < 0 ? mod : mod.substring(0, at);
            String version = at < 0 ? "" : mod.substring(at + 1);
            if (id.equals("minecraft")) {
                game = version;
            } else if (id.equals(loaderId)) {
                loaderVersion = version;
            } else if (id.equals("forge")) {
                forgeVersion = version;
            }
        }
        if (loaderVersion.isEmpty() && "neoforge".equals(loader)) {
            // NeoForge 1.20.1 still lists itself under the mod id forge.
            loaderVersion = forgeVersion;
        }
        return Integration.startupLine(platform.modVersion(), loader, loaderVersion, game, ALL.values(), FOUND);
    }
}
