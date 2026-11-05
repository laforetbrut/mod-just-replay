package com.vyrriox.justreplay.fabric.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The id table of a registry, to fill the ids a recording names but this game does not have.
 *
 * @author vyrriox
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor<T> {
    @Accessor("byId")
    ObjectList<Holder.Reference<T>> justreplay$getById();
}
