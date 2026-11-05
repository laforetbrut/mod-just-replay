package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the FOV modifier at exactly 1 when the replay asks for it.
 *
 * @author vyrriox
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerFovMixin {
    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void justreplay$fovModifier(CallbackInfoReturnable<Float> cir) {
        if (ClientHooks.locksFovModifier()) {
            cir.setReturnValue(1.0F);
        }
    }
}
