package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ArmourersWorkshopCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armourer's Workshop hands a skin answer only to a pending request. During a replay the recorded answers fill the
 * client skin cache directly, so outfits show whether or not the replay client asked first. Optional: does nothing
 * when Armourer's Workshop is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.core.network.ResponseSkinPacket", remap = false)
public abstract class ArmourersSkinResponseMixin {
    @Inject(method = "accept",
            at = @At("HEAD"), require = 0, remap = false)
    private void justreplay$fillReplayCache(CallbackInfo ci) {
        ArmourersWorkshopCompat.onSkinResponse(this);
    }
}
