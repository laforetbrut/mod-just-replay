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
 * The armor Armourer's Workshop collected for an entity, whose skins it then draws: in a replay the pieces of the
 * hidden armor slots are left out, so their skins go with them (see {@link ArmourersHiddenArmor}). Optional: does
 * nothing when Armourer's Workshop is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.core.client.other.EntitySlotsHandler$EntityProvider", remap = false)
public abstract class ArmourersEquipmentSlotsMixin {
    /**
     * Matched by name, the same on every loader: the collector and the bridge its generic parent calls both pass
     * here, and leaving the same pieces out twice changes nothing.
     */
    @Inject(method = "collect", at = @At("RETURN"), require = 0, remap = false)
    private void justreplay$hideArmorSkins(@Coerce Object entity, List<?> collected, CallbackInfo ci) {
        if (!Integrations.ARMOURERS_WORKSHOP.on() || !ReplaySession.isRunning()) {
            return;
        }
        try {
            ArmourersHiddenArmor.filterEquipment(this, entity, collected);
        } catch (Throwable t) {
            Integrations.ARMOURERS_WORKSHOP.fail("EntitySlotsHandler.EntityProvider.collect", t);
        }
    }
}
