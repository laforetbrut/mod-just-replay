package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ArmourersWorkshopCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armourer's Workshop skin requests sent during a replay go to the replay host, which knows none of the recorded
 * skins: they are dropped (or answered from the replay). Optional: does nothing when Armourer's Workshop is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.init.platform.NetworkManager", remap = false)
public abstract class ArmourersSendMixin {
    @Inject(method = "sendToServer(Lmoe/plushie/armourers_workshop/core/network/CustomPacket;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$dropReplayRequests(@Coerce Object packet, CallbackInfo ci) {
        if (ArmourersWorkshopCompat.blockServerbound(packet)) {
            ci.cancel();
        }
    }
}
