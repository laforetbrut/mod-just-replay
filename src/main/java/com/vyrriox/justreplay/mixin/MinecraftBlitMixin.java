package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.export.ExportRenderSize;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * While an export renders at a size other than the window, the finished frame is scaled to the real window
 * instead of being drawn at its render size. Purely cosmetic, so a conflict with another mod is not fatal.
 *
 * @author vyrriox
 */
@Mixin(Minecraft.class)
public abstract class MinecraftBlitMixin {
    @ModifyArg(method = "runTick", require = 0, index = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private int justreplay$screenWidth(int width) {
        return ExportRenderSize.screenWidth(width);
    }

    @ModifyArg(method = "runTick", require = 0, index = 1,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private int justreplay$screenHeight(int height) {
        return ExportRenderSize.screenHeight(height);
    }
}
