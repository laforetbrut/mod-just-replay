package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the render veil is up, a key press other than Esc never reaches the game: F1, F3, F5, the hotbar or the
 * movement keys would change the video or move its camera. Releases still go through so no key stays held.
 *
 * @author vyrriox
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerHooksMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void justreplay$keyPress(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (ClientHooks.cancelsKey(key, action)) {
            ci.cancel();
        }
    }
}
