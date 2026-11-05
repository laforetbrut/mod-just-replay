package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The clean HUD setting on Fabric, which has no layer API here: the vanilla parts that are not a recorded overlay, and
 * the chat under the editor. What other mods draw from their HUD callback stays visible.
 *
 * @author vyrriox
 */
@Mixin(Gui.class)
public abstract class GuiCleanHudMixin {
    @Inject(method = {"renderCameraOverlays", "renderCrosshair", "renderHotbarAndDecorations", "renderExperienceLevel",
            "renderEffects", "renderSleepOverlay", "renderDemoOverlay", "renderSavingIndicator"},
            at = @At("HEAD"), cancellable = true)
    private void justreplay$cleanHud(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (ClientHooks.hidesGuiLayer(false, false)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderChat", at = @At("HEAD"), cancellable = true)
    private void justreplay$chat(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (ClientHooks.hidesGuiLayer(true, true)) {
            ci.cancel();
        }
    }
}
