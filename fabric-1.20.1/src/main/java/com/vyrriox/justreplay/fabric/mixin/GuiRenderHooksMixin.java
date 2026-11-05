package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.Frames;
import com.vyrriox.justreplay.fabric.FabricRenderHooks;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the layers of the mod at the end of the HUD, where the event of the other loaders runs.
 *
 * @author vyrriox
 */
@Mixin(Gui.class)
public abstract class GuiRenderHooksMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void justreplay$hud(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        FabricRenderHooks.onHud(graphics, Frames.partialTick());
    }
}
