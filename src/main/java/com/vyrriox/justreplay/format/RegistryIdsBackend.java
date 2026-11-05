package com.vyrriox.justreplay.format;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads and applies the numeric ids of the static registries the way the loader does it. The bytes of
 * {@value RegistryIds#FILE} belong to the backend: each loader has its own format, and replays already recorded must
 * keep opening on it.
 *
 * @author vyrriox
 */
public interface RegistryIdsBackend {
    /** Sink of the self test lines, with the placeholders of the logger. */
    @FunctionalInterface
    interface CheckLog {
        void log(String message, Object... args);
    }

    /** The ids currently in use by the client, encoded for {@value RegistryIds#FILE}; null when they cannot be encoded. */
    byte[] encode();

    /** Reads bytes written by {@link #encode()}. */
    RegistryIdMap decode(byte[] bytes);

    /** The ids currently in use by the client. */
    RegistryIdMap current();

    /**
     * Applies these ids, exactly as given. The registries are incomplete while this runs: nothing may be encoding or
     * decoding on another thread, see ReplaySession. Returns false when the ids were not applied.
     */
    boolean apply(RegistryIdMap ids);

    /**
     * A replay whose saved ids miss an entry of this game (a mod that registers it later): the entry must keep an id,
     * or the replay world's server fails to send packets naming it (1.0.6: the recipes, then the replay closed). The
     * caller puts the replay's own ids back afterwards.
     */
    default void selfCheck(CheckLog log) {
        RegistryIdMap current = current();
        Identifier registry = Identifier.withDefaultNamespace("recipe_serializer");
        if (!current.tables().containsKey(registry)) {
            registry = Identifier.withDefaultNamespace("item");
        }
        boolean serializers = registry.getPath().equals("recipe_serializer");
        Identifier dropped = serializers
                ? Identifier.withDefaultNamespace("crafting_shapeless")
                : Identifier.withDefaultNamespace("stick");
        RegistryIdMap.Table table = current.tables().get(registry);
        Int2ObjectSortedMap<Identifier> ids = new Int2ObjectRBTreeMap<>(table.ids());
        ids.values().removeIf(dropped::equals);
        Map<Identifier, RegistryIdMap.Table> tables = new HashMap<>(current.tables());
        tables.put(registry, new RegistryIdMap.Table(ids, table.aliases()));
        RegistryIdMap partial = new RegistryIdMap(tables);
        apply(partial);
        int raw = serializers
                ? BuiltInRegistries.RECIPE_SERIALIZER.getId(net.minecraft.world.item.crafting.ShapelessRecipe.SERIALIZER)
                : BuiltInRegistries.ITEM.getId(Items.STICK);
        log.log("registry ids missing {} applied as recorded: id {} (the bug when -1)", dropped, raw);
        apply(RegistryIds.complete(partial));
        int id = serializers
                ? BuiltInRegistries.RECIPE_SERIALIZER.getId(net.minecraft.world.item.crafting.ShapelessRecipe.SERIALIZER)
                : BuiltInRegistries.ITEM.getId(Items.STICK);
        log.log("registry ids missing {} in the recording: id after apply {} -> {}", dropped, id, id >= 0 ? "OK" : "FAIL");
    }
}
