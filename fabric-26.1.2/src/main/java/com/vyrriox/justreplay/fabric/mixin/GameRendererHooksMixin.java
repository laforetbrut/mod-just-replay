package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.fabric.FabricRenderHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame start and frame end, around the draw of a frame as on the other loaders (the extraction ran before).
 *
 * @author vyrriox
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHooksMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void justreplay$framePre(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        FabricRenderHooks.onFramePre(deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void justreplay$framePost(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        FabricRenderHooks.onFramePost(deltaTracker.getGameTimeDeltaPartialTick(false));
    }
}
