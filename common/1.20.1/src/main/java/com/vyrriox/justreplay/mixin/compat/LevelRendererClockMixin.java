package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The level renderer's tick counter drives clouds, rain and, through Flywheel's frame uniforms, the instanced Create
 * visuals (rotating shafts and cogs, scrolling belts). It advances one per client tick, while a replay above x1 plays
 * several recorded ticks per client tick and a seek jumps. During a replay it is set to the replay cursor, so these
 * animations follow the replay speed and show the same state after a seek and in exports.
 *
 * @author vyrriox
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererClockMixin {
    @Shadow
    private int ticks;

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void justreplay$replayClock(CallbackInfo ci) {
        int replayTicks = ReplayCompat.animationTicks();
        if (replayTicks >= 0) {
            this.ticks = replayTicks;
        }
    }
}
