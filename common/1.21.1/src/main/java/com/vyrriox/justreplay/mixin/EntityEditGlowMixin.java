package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.EntityEdits;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Outlines the recorded entity selected in the editor's Entities tab (never in an export).
 *
 * @author vyrriox
 */
@Mixin(Minecraft.class)
public abstract class EntityEditGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void justreplay$selectedGlows(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (EntityEdits.highlighted(entity)) {
            cir.setReturnValue(true);
        }
    }
}
