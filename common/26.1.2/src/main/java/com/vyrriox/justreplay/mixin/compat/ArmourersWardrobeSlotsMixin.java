package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ArmourersHiddenArmor;
import com.vyrriox.justreplay.compat.Integrations;
import com.vyrriox.justreplay.replay.ReplaySession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The wardrobe Armourer's Workshop collected for an entity: in a replay the skins covering a hidden armor slot are
 * left out (see {@link ArmourersHiddenArmor}). Optional: does nothing when Armourer's Workshop is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.core.client.other.EntitySlotsHandler$WardrobeProvider", remap = false)
public abstract class ArmourersWardrobeSlotsMixin {
    /** Matched by name like the equipment one: the collector and its bridge both pass here, harmlessly. */
    @Inject(method = "collect", at = @At("RETURN"), require = 0, remap = false)
    private void justreplay$hideWardrobeSkins(@Coerce Object wardrobe, List<?> collected, CallbackInfo ci) {
        if (!Integrations.ARMOURERS_WORKSHOP.on() || !ReplaySession.isRunning()) {
            return;
        }
        try {
            ArmourersHiddenArmor.filterWardrobe(wardrobe, collected);
        } catch (Throwable t) {
            Integrations.ARMOURERS_WORKSHOP.fail("EntitySlotsHandler.WardrobeProvider.collect", t);
        }
    }
}
