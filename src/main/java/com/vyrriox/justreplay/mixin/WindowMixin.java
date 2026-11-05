package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.platform.Window;
import com.vyrriox.justreplay.export.ExportRenderSize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During a video export the render size is locked: a window resize is remembered for later instead of
 * resizing every render target in the middle of the video.
 *
 * @author vyrriox
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
    private void justreplay$holdResize(long window, int width, int height, CallbackInfo ci) {
        if (window == ((Window) (Object) this).getWindow() && ExportRenderSize.onWindowResize(width, height)) {
            ci.cancel();
        }
    }
}
