package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.Frames;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.fabric.FabricRenderHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Frame start, frame end and the camera FOV.
 *
 * @author vyrriox
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHooksMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void justreplay$framePre(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FabricRenderHooks.onFramePre(Frames.partialTick());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void justreplay$framePost(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FabricRenderHooks.onFramePost(Frames.partialTick());
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void justreplay$fov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        Double fov = ClientHooks.cameraFov(useFovSetting);
        if (fov != null) {
            cir.setReturnValue(fov);
        }
    }
}
