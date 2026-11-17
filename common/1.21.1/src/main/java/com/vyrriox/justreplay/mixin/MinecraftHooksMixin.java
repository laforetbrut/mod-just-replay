package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Screen opening and attack hooks. The attack points sit where the checks of the method have passed, so the hook only
 * runs for a real attack: it also picks the entity a left click views the replay through.
 *
 * @author vyrriox
 */
@Mixin(Minecraft.class)
public abstract class MinecraftHooksMixin {
    @Shadow
    public ClientLevel level;
    @Shadow
    public LocalPlayer player;
    @Shadow
    private boolean clientLevelTeardownInProgress;

    /**
     * At the head because vanilla closes the current screen before it works out the new one. Going back to the game
     * is no opening, unless it resolves to the title or the death screen, which are built here only to be looked at.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void justreplay$setScreen(Screen screen, CallbackInfo ci) {
        Screen opening = screen;
        if (opening == null && !this.clientLevelTeardownInProgress) {
            if (this.level == null) {
                opening = new TitleScreen();
            } else if (this.player.isDeadOrDying() && this.player.shouldShowDeathScreen()) {
                opening = new DeathScreen(null, this.level.getLevelData().isHardcore());
            }
        }
        if (opening != null && ClientHooks.cancelsScreenOpening(opening)) {
            ci.cancel();
        }
    }

    @Inject(method = "startAttack", cancellable = true, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
    private void justreplay$startAttack(CallbackInfoReturnable<Boolean> cir) {
        if (ClientHooks.cancelsAttack()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", cancellable = true, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/phys/BlockHitResult;getDirection()Lnet/minecraft/core/Direction;"))
    private void justreplay$continueAttack(boolean leftClick, CallbackInfo ci) {
        if (ClientHooks.cancelsAttack()) {
            ci.cancel();
        }
    }
}
