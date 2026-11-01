package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.EmotecraftCompat;
import com.vyrriox.justreplay.compat.ReplayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A keyframe animation (an emote) blends between its current and next tick with the frame's partial tick. Players are
 * never frozen by the paused replay, so their partial tick keeps cycling: the pose is held on the current tick instead.
 * An emote played by an EMOTE action of an actor skips its own tick and is moved to the replay time of every frame,
 * which gives it a speed and keeps it in sync in exports. Optional: does nothing when the player animation library is
 * absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer", remap = false)
public abstract class PlayerAnimKeyframeMixin {
    @ModifyVariable(method = "setupAnim(F)V", at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private float justreplay$holdWhilePaused(float tickDelta) {
        float clocked = EmotecraftCompat.clockDelta(this);
        if (!Float.isNaN(clocked)) {
            return clocked;
        }
        return ReplayCompat.isReplayPaused() ? 0.0F : tickDelta;
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$followActorClock(CallbackInfo ci) {
        if (EmotecraftCompat.isClocked(this)) {
            ci.cancel();
        }
    }
}
