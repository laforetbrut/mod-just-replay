package com.vyrriox.justreplay.mixin;

import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The entity a bound sound follows, so its volume can follow the edits of that entity.
 *
 * @author vyrriox
 */
@Mixin(EntityBoundSoundInstance.class)
public interface EntityBoundSoundAccessor {
    @Accessor("entity")
    Entity justreplay$getEntity();
}
