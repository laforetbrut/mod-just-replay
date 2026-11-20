package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.platform.EarlyMods;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    /** Simple Voice Chat, reached through its API (compiled against it), so it is not in {@link #SUPPORTED}. */
    public static final String VOICE_CHAT = "voicechat";

    /**
     * Integrations read against their 26.1 build, per loader. Immersive Vehicles ({@code mts}) was read from its NeoForge
     * 24.0.0-26.1 jar; it has no Forge or Fabric build for 26.1. Armourer's Workshop ({@code armourers_workshop}) was
     * read from its NeoForge and Fabric 3.4.0-beta.3 jars for 26.1.2; its 26.1 jar named "forge" is a NeoForge build.
     */
    private static final Map<String, Set<String>> SUPPORTED = Map.of(
            "neoforge", Set.of("armourers_workshop", "entityculling", "geckolib", "iris", "jade", "mts"),
            "fabric", Set.of("armourers_workshop", "entityculling", "geckolib", "iris", "jade"),
            "forge", Set.of("geckolib"));

    /**
     * Every integration of this Minecraft version, mod id to the name the log and the settings show, in the order of
     * the settings. Each one has a switch in the {@code compat} section of the client config.
     */
    public static final Map<String, String> NAMES = names(
            "armourers_workshop", "Armourer's Workshop",
            "bettercombat", "Better Combat",
            "bobby", "Bobby",
            "curios", "Curios",
            "distanthorizons", "Distant Horizons",
            "emotecraft", "Emotecraft",
            "entityculling", "Entity Culling",
            "geckolib", "GeckoLib",
            "immersive_aircraft", "Immersive Aircraft",
            "immersive_paintings", "Immersive Paintings",
            "mts", "Immersive Vehicles",
            "iris", "Iris",
            "jade", "Jade",
            VOICE_CHAT, "Simple Voice Chat");

    /**
     * The integration each mixin of {@code justreplay.compat.mixins.json} belongs to, by the start of its class name.
     * A mixin applies only while its integration may run, so a switch of the config also removes its mixins.
     */
    private static final Map<String, String> MIXIN_OWNERS = names(
            "Armourers", "armourers_workshop",
            "Bobby", "bobby",
            "GeckoLib", "geckolib",
            "Iris", "iris",
            "Jade", "jade");

    /**
     * Integrations whose mixins apply whenever they are supported and allowed, without a look at the mod list. Their
     * mixins are {@code @Pseudo}: without the target class they do nothing.
     */
    private static final Set<String> MIXINS_WITHOUT_MOD_CHECK = Set.of("bobby");

    /**
     * Mixin integrations whose target may come under another mod id (Bobby through a loader bridge): they count as
     * installed, their mixins only act where the target class exists.
     */
    private static final Set<String> PRESENCE_UNKNOWN = Set.of("bobby");

    /** Other mod ids an integration's mod is known under, on some loader. */
    private static final Map<String, Set<String>> ALIASES = Map.of();

    /** Mixins of the compat config that target the game itself and belong to no integration. */
    public static final Set<String> GAME_MIXINS = Set.of("LevelRendererClockMixin", "SectionBufferGuardMixin");

    private CompatTargets() {
    }

    /** True when the integration with this mod has been read against its build for the running loader. */
    public static boolean isSupported(String modId) {
        return VOICE_CHAT.equals(modId) || SUPPORTED.getOrDefault(EarlyMods.loaderName(), Set.of()).contains(modId);
    }

    /** True when this mod is installed, its integration may run on this loader and the config leaves it on. */
    public static boolean isActive(String modId) {
        return EarlyMods.isLoaded(modId) && isSupported(modId) && CompatSwitches.isEnabled(modId);
    }

    /** The integration a compat mixin belongs to (its mod id), or null for a mixin of the game itself or an unknown one. */
    public static String mixinOwner(String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        for (Map.Entry<String, String> owner : MIXIN_OWNERS.entrySet()) {
            if (simple.startsWith(owner.getKey())) {
                return owner.getValue();
            }
        }
        return null;
    }

    /** True when the mixins of this integration may apply: supported, allowed by the config and (mostly) installed. */
    public static boolean mixinsApply(String modId) {
        if (MIXINS_WITHOUT_MOD_CHECK.contains(modId)) {
            return isSupported(modId) && CompatSwitches.isEnabled(modId);
        }
        return isActive(modId);
    }

    /** Every mod id supported on at least one loader. */
    static Set<String> supportedAnywhere() {
        Set<String> ids = new java.util.TreeSet<>();
        SUPPORTED.values().forEach(ids::addAll);
        return ids;
    }

    /** True for an integration that counts as installed even when its mod id is not found (see the field). */
    public static boolean isPresenceUnknown(String modId) {
        return PRESENCE_UNKNOWN.contains(modId);
    }

    /** The mod ids this integration's mod may have: its own first, then its aliases. */
    public static Set<String> modIds(String modId) {
        Set<String> aliases = ALIASES.get(modId);
        if (aliases == null) {
            return Set.of(modId);
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        ids.add(modId);
        ids.addAll(aliases);
        return ids;
    }

    private static Map<String, String> names(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
