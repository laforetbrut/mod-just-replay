package com.vyrriox.justreplay.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the data items of an entity, so an unchanged entity costs no allocation per tick.
 *
 * @author vyrriox
 */
@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {
    @Accessor("itemsById")
    Int2ObjectMap<SynchedEntityData.DataItem<?>> justreplay$getItemsById();
}
