package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.fx.FxEngine;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the replay video effects once the world is in the main render target (after the vanilla post effect and
 * the final pass of a shader pack, which both happen earlier) and before the HUD and the screens are drawn over
 * it. The fog renderer ends its frame right after the world block of GameRenderer.render, on every frame, so the
 * handler repeats the condition of that block. Not required: when another mod changes this spot the effects are
 * skipped and a warning is logged.
 *
 * @author vyrriox
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFxMixin {
    @Inject(method = "render", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V", ordinal = 0))
    private void justreplay$effects(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (advanceGameTime && minecraft.level != null && minecraft.isGameLoadFinished()) {
            FxEngine.render(deltaTracker.getGameTimeDeltaPartialTick(false));
        }
    }
}
