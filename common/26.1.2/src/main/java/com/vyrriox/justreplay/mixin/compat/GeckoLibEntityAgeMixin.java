package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GeckoLib entities animate on the age held by their render state, so during a replay they run at x1 whatever the speed
 * and restart after a seek. GeckoLib adds {@code getAnimatableAge} to the vanilla render state with its own mixin; during
 * a replay it answers the replay clock instead. Client thread only. Optional: without GeckoLib the method does not exist
 * and nothing is injected.
 *
 * @author vyrriox
 */
@Mixin(value = EntityRenderState.class, remap = false)
public abstract class GeckoLibEntityAgeMixin {
    @Inject(method = "getAnimatableAge()D", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$replayClock(CallbackInfoReturnable<Double> cir) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        double ticks = ReplayCompat.geckoEntityFrameTime(-1);
        if (ticks >= 0) {
            cir.setReturnValue(ticks);
        }
    }
}
