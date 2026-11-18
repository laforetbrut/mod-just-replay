package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ArmourersWorkshopCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Armourer's Workshop skin loader finished a skin, from any source. In singleplayer it reads the world's skin
 * database directly and from a server it may read its disk cache, so no skin answer crosses the connection: the
 * recording takes the skin here. Optional: does nothing when Armourer's Workshop is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.core.skin.SkinLoader$Entry", remap = false)
public abstract class ArmourersSkinEntryMixin {
    @Inject(method = "accept(Lmoe/plushie/armourers_workshop/core/skin/Skin;)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void justreplay$recordLoadedSkin(@Coerce Object skin, CallbackInfo ci) {
        ArmourersWorkshopCompat.onSkinLoaded(this, skin);
    }
}
