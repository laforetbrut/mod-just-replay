package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
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
    private void justreplay$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (ClientHooks.cancelsKey(event.key(), action)) {
            ci.cancel();
        }
    }
}
