package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.export.ExportRenderSize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * While an export renders at a size other than the window, the finished frame is scaled to the real window
 * instead of being drawn at its render size. {@code RenderTarget.blitToScreen} has no size any more, the size is
 * in the framebuffer blit of the command encoder that presents the frame. Purely cosmetic, so a conflict with
 * another mod is not fatal.
 *
 * @author vyrriox
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class MinecraftBlitMixin {
    @Unique
    private static final int JUSTREPLAY$GL_LINEAR = 9729;

    @ModifyArg(method = "presentTexture", require = 0, index = 8,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private int justreplay$screenWidth(int width) {
        return ExportRenderSize.screenWidth(width);
    }

    @ModifyArg(method = "presentTexture", require = 0, index = 9,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private int justreplay$screenHeight(int height) {
        return ExportRenderSize.screenHeight(height);
    }

    @ModifyArg(method = "presentTexture", require = 0, index = 11,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private int justreplay$screenFilter(int filter) {
        return ExportRenderSize.isLocked() ? JUSTREPLAY$GL_LINEAR : filter;
    }
}
