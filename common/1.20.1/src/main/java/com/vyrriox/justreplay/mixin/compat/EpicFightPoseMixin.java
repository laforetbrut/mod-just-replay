package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * An Epic Fight animation pose blends between the previous and the current animation time with the frame's partial
 * tick. Entities are never frozen by the paused replay, so their partial tick keeps cycling while the animator holds:
 * the pose is held on the current time instead. Optional: does nothing without Epic Fight.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.api.animation.AnimationPlayer", remap = false)
public abstract class EpicFightPoseMixin {
    @ModifyVariable(method = "getCurrentPose", at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private float justreplay$holdWhilePaused(float partialTick) {
        return ReplayCompat.isReplayPaused() ? 1.0F : partialTick;
    }
}
