package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ArmourersWorkshopCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every Armourer's Workshop skin lookup passes here before the loader picks a source (local database, library,
 * server). During a replay a skin the replay holds is served at once, and a server skin it does not hold yet waits for
 * its recorded answer instead of failing against the replay world's empty database. Optional: does nothing when
 * Armourer's Workshop is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.core.skin.SkinLoader", remap = false)
public abstract class ArmourersSkinLoaderMixin {
    @Inject(method = "resume(Lmoe/plushie/armourers_workshop/core/skin/SkinLoader$Entry;Lmoe/plushie/armourers_workshop/core/skin/SkinLoader$Method;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$serveReplaySkins(@Coerce Object entry, @Coerce Object method, CallbackInfo ci) {
        if (ArmourersWorkshopCompat.serveReplayLookup(entry)) {
            ci.cancel();
        }
    }
}
