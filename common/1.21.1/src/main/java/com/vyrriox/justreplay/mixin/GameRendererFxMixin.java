package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.fx.FxEngine;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the replay video effects once the world is in the main render target (after the vanilla post effect and
 * the final pass of a shader pack, which both happen earlier) and before the HUD and the screens are drawn over
 * it. Not required: when another mod changes this spot the effects are skipped and a warning is logged.
 *
 * @author vyrriox
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFxMixin {
    @Inject(method = "render", require = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V", ordinal = 0))
    private void justreplay$effects(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FxEngine.render(deltaTracker.getGameTimeDeltaPartialTick(false));
    }
}
