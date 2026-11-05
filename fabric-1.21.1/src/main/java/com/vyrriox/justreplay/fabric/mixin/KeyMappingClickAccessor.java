package com.vyrriox.justreplay.fabric.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Press counter of a binding, for the ones the vanilla key map does not reach.
 *
 * @author vyrriox
 */
@Mixin(KeyMapping.class)
public interface KeyMappingClickAccessor {
    @Accessor("clickCount")
    int justreplay$getClickCount();

    @Accessor("clickCount")
    void justreplay$setClickCount(int count);
}
