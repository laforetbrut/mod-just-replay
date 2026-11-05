package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.platform.EarlyMods;

import java.util.Map;
import java.util.Set;

/**
 * The integrations read against the build of their mod for each loader of this Minecraft version. Every integration
 * reaches its mod by name, so compiling proves nothing: an integration stays off on a loader until it has been checked
 * there. Read while mixins are selected, so this class must not touch game classes.
 *
 * @author vyrriox
 */
public final class CompatTargets {
    /** Key of the Player Animator mixins, whose library has a different mod id on each loader. */
    public static final String PLAYER_ANIMATOR = "playeranimator";

    /**
     * Integrations read against their 1.20.1 build, per loader. The Forge builds were read from the jars of GeckoLib
     * 4.8.4, Curios 5.14.1, Jade 11.13.3 and Entity Culling 1.11.3. The NeoForge and Fabric rows are not yet checked
     * against builds for those loaders.
     */
    private static final Map<String, Set<String>> SUPPORTED = Map.of(
            "neoforge", Set.of("geckolib", "curios", "jade"),
            "fabric", Set.of("geckolib", "jade", "entityculling"),
            "forge", Set.of("geckolib", "curios", "jade", "entityculling"));

    private CompatTargets() {
    }

    /** True when the integration with this mod has been read against its build for the running loader. */
    public static boolean isSupported(String modId) {
        return SUPPORTED.getOrDefault(EarlyMods.loaderName(), Set.of()).contains(modId);
    }

    /** True when this mod is installed and its integration may run on this loader. */
    public static boolean isActive(String modId) {
        return EarlyMods.isLoaded(modId) && isSupported(modId);
    }
}
