package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's animation clock (Ponder's {@code AnimationTickHolder}, bundled with Create) counts client ticks and only
 * stops on the pause menu: shafts, cogs, fans and arms would keep turning in a paused replay and show other angles
 * after a seek. During a replay it is set to the replay cursor instead. Optional: does nothing without Create.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "net.createmod.catnip.animation.AnimationTickHolder", remap = false)
public abstract class CatnipAnimationTickHolderMixin {
    @Shadow(remap = false)
    private static int ticks;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$replayTick(CallbackInfo ci) {
        int replayTicks = ReplayCompat.animationTicks();
        if (replayTicks >= 0) {
            ticks = replayTicks;
            ci.cancel();
        }
    }

    @Inject(method = "reset", at = @At("TAIL"), require = 0, remap = false)
    private static void justreplay$replayReset(CallbackInfo ci) {
        int replayTicks = ReplayCompat.animationTicks();
        if (replayTicks >= 0) {
            ticks = replayTicks;
        }
    }
}
