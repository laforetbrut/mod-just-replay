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
    /**
     * Integrations read against their 26.1 build, per loader. Immersive Vehicles ({@code mts}) was read from its NeoForge
     * 24.0.0-26.1 jar; it has no Forge or Fabric build for 26.1. Armourer's Workshop ({@code armourers_workshop}) was
     * read from its NeoForge and Fabric 3.4.0-beta.3 jars for 26.1.2; its 26.1 jar named "forge" is a NeoForge build.
     */
    private static final Map<String, Set<String>> SUPPORTED = Map.of(
            "neoforge", Set.of("armourers_workshop", "entityculling", "geckolib", "iris", "jade", "mts"),
            "fabric", Set.of("armourers_workshop", "entityculling", "geckolib", "iris", "jade"),
            "forge", Set.of("geckolib"));

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
