package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mouse hooks of the placement tools: a button once the press is tracked, a scroll wheel step only in game, with no
 * screen open, where it would change the hotbar slot or the flying speed.
 *
 * @author vyrriox
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerHooksMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onButton", cancellable = true, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/Minecraft;getOverlay()Lnet/minecraft/client/gui/screens/Overlay;"))
    private void justreplay$onButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (ClientHooks.cancelsMouse(button.button(), action)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", cancellable = true, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private void justreplay$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        boolean discrete = this.minecraft.options.discreteMouseScroll().get();
        double sensitivity = this.minecraft.options.mouseWheelSensitivity().get();
        double x = (discrete ? Math.signum(xOffset) : xOffset) * sensitivity;
        double y = (discrete ? Math.signum(yOffset) : yOffset) * sensitivity;
        if (ClientHooks.cancelsScroll(x, y)) {
            ci.cancel();
        }
    }
}
