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

    private static final Map<String, Set<String>> SUPPORTED = Map.of(
            "neoforge", Set.of("accessories", "aeronautics", "armourers_workshop", "bettercombat", "bobby", "create", "curios",
                    "customnpcs", "distanthorizons", "dropthebeam", "emotecraft", "entityculling", "epicfight", "exposure",
                    "geckolib", "immersive_aircraft", "immersive_paintings", "irons_spellbooks", "iris", "jade", "morph",
                    "parcool", "pehkui", PLAYER_ANIMATOR, "sable", "simulated", "waterframes"),
            "fabric", Set.of("accessories", "armourers_workshop", "bettercombat", "bobby", "distanthorizons", "emotecraft",
                    "entityculling", "exposure", "geckolib", "immersive_aircraft", "immersive_paintings", "iris", "jade",
                    "pehkui", PLAYER_ANIMATOR, "sable", "waterframes"),
            "forge", Set.of());

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
