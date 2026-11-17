package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chat, which the clean HUD and the chat setting of the editor hide. Vanilla draws it from the HUD, not as a part of
 * it, so it has its own mixin.
 *
 * @author vyrriox
 */
@Mixin(ChatComponent.class)
public abstract class ChatCleanHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void justreplay$chat(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, CallbackInfo ci) {
        if (ClientHooks.hidesGuiLayer(true, true)) {
            ci.cancel();
        }
    }
}
