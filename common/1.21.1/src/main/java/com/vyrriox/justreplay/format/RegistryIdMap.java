package com.vyrriox.justreplay.format;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Numeric ids of the static registries, by registry name, in a form that does not depend on the loader.
 *
 * @author vyrriox
 */
public record RegistryIdMap(Map<ResourceLocation, Table> tables) {
    /** The ids of one registry, sorted by increasing id, and its aliases. */
    public record Table(Int2ObjectSortedMap<ResourceLocation> ids, Map<ResourceLocation, ResourceLocation> aliases) {
    }
}
