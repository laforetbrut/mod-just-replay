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
    /** Key of the Player Animator mixins, whose library has a different mod id on each loader. */
    public static final String PLAYER_ANIMATOR = "playeranimator";
    /** Simple Voice Chat, reached through its API (compiled against it), so it is not in {@link #SUPPORTED}. */
    public static final String VOICE_CHAT = "voicechat";

    /**
     * Integrations read against their 1.20.1 build, per loader. The Forge builds were read from the jars of GeckoLib
     * 4.8.4, Curios 5.14.1, Jade 11.13.3, Entity Culling 1.11.3, Immersive Vehicles 24.0.0 and More Player Models
     * 1.20.1.20240409. The NeoForge and Fabric rows are not yet checked against builds for those loaders, but for
     * More Player Models: its only 1.20.1 build is that Forge jar, which NeoForge 47.1 loads as it is, and every
     * class and member the integration names was compiled against it with the classes of both loaders. Armourer's
     * Workshop 3.4.0-beta.3 (its Forge jar, also on NeoForge, and its Fabric jar) and the unofficial Custom NPCs port
     * (Forge builds 20240824 and 20260711, also on NeoForge, and Fabric build 20251214) were checked the same way on
     * each loader. Epic Fight 20.14.17 is Forge only: it needs Forge 47.4 or later and has no Fabric build. Elytra
     * Slot was read from its jars 6.4.4 (Forge, Fabric) and 6.4.0 (its last build marked for NeoForge 1.20.1), Trinkets
     * from the jar of 3.7.2 (Fabric only).
     */
    private static final Map<String, Set<String>> SUPPORTED = Map.of(
            "neoforge", Set.of("armourers_workshop", "customnpcs", "elytraslot", "geckolib", "curios", "jade",
                    "moreplayermodels"),
            "fabric", Set.of("armourers_workshop", "customnpcs", "elytraslot", "geckolib", "jade", "entityculling",
                    "trinkets"),
            "forge", Set.of("armourers_workshop", "customnpcs", "elytraslot", "geckolib", "curios", "jade", "entityculling",
                    "mts", "moreplayermodels", "epicfight"));

    /**
     * Every integration of this Minecraft version, mod id to the name the log and the settings show, in the order of
     * the settings. Each one has a switch in the {@code compat} section of the client config.
     */
    public static final Map<String, String> NAMES = names(
            "armourers_workshop", "Armourer's Workshop",
            "bobby", "Bobby",
            "curios", "Curios",
            "customnpcs", "Custom NPCs",
            "distanthorizons", "Distant Horizons",
            "elytraslot", "Elytra Slot",
            "entityculling", "Entity Culling",
            "epicfight", "Epic Fight",
            "geckolib", "GeckoLib",
            "mts", "Immersive Vehicles",
            "iris", "Iris",
            "jade", "Jade",
            "moreplayermodels", "More Player Models",
            PLAYER_ANIMATOR, "Player Animator",
            VOICE_CHAT, "Simple Voice Chat",
            "trinkets", "Trinkets",
            "waterframes", "WaterFrames");

    /**
     * The integration each mixin of {@code justreplay.compat.mixins.json} belongs to, by the start of its class name.
     * A mixin applies only while its integration may run, so a switch of the config also removes its mixins.
     */
    private static final Map<String, String> MIXIN_OWNERS = names(
            "Armourers", "armourers_workshop",
            "Bobby", "bobby",
            "ElytraSlot", "elytraslot",
            "EpicFight", "epicfight",
            "GeckoLib", "geckolib",
            "Iris", "iris",
            "Jade", "jade",
            "MorePlayerModels", "moreplayermodels",
            "PlayerAnim", PLAYER_ANIMATOR,
            "WaterFrames", "waterframes");

    /**
     * Integrations whose mixins apply whenever they are supported and allowed, without a look at the mod list. Their
     * mixins are {@code @Pseudo}: without the target class they do nothing.
     */
    private static final Set<String> MIXINS_WITHOUT_MOD_CHECK = Set.of("bobby", PLAYER_ANIMATOR, "waterframes");

    /**
     * Mixin integrations whose target may come under another mod id (Player Animator, Bobby through a loader bridge):
     * they count as installed, their mixins only act where the target class exists.
     */
    private static final Set<String> PRESENCE_UNKNOWN = Set.of("bobby", PLAYER_ANIMATOR);

    /** Other mod ids an integration's mod is known under, on some loader. */
    private static final Map<String, Set<String>> ALIASES = Map.of(PLAYER_ANIMATOR, Set.of("player-animator", "player_animator"));

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
