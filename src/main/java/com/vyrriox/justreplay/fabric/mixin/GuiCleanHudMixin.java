package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The clean HUD setting on Fabric 1.20.1, which has no per-overlay event: each vanilla part the other loaders hide with
 * their overlay event is cancelled here. The parts a replay keeps (scoreboard, boss bar, titles, player list, debug text)
 * are not listed, as on the other loaders. What other mods draw from their own HUD callback stays visible.
 *
 * @author vyrriox
 */
@Mixin(Gui.class)
public abstract class GuiCleanHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void justreplay$crosshair(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void justreplay$effects(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void justreplay$selectedItemName(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderDemoOverlay", at = @At("HEAD"), cancellable = true)
    private void justreplay$demo(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    /** Hearts, armor, food and air. */
    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void justreplay$playerHealth(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void justreplay$vehicleHealth(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderSavingIndicator", at = @At("HEAD"), cancellable = true)
    private void justreplay$savingIndicator(GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void justreplay$hotbar(float partialTick, GuiGraphics graphics, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void justreplay$experienceBar(GuiGraphics graphics, int experience, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderJumpMeter", at = @At("HEAD"), cancellable = true)
    private void justreplay$jumpMeter(PlayerRideableJumping mount, GuiGraphics graphics, int x, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void justreplay$spyglass(GuiGraphics graphics, float scope, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void justreplay$portal(GuiGraphics graphics, float alpha, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void justreplay$vignette(GuiGraphics graphics, Entity entity, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    /** Pumpkin and powder snow frost. */
    @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void justreplay$textureOverlay(GuiGraphics graphics, ResourceLocation texture, float alpha, CallbackInfo ci) {
        cancelIfHidden(ci);
    }

    private static void cancelIfHidden(CallbackInfo ci) {
        if (ClientHooks.hidesGuiLayer(false, false)) {
            ci.cancel();
        }
    }
}
