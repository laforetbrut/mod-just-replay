package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Morph draws a player's form with the frame's partial tick. Players are never frozen by the paused replay, so their
 * partial tick keeps cycling while the form no longer ticks: the form is held on its last tick instead. Optional: does
 * nothing when Morph is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "me.ichun.mods.morph.client.render.MorphRenderHandler", remap = false)
public abstract class MorphRenderMixin {
    @ModifyVariable(method = "renderMorphInfo", at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private static float justreplay$holdWhilePaused(float partialTick) {
        return ReplayCompat.isReplayPaused() ? 1.0F : partialTick;
    }
}
