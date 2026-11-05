package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.BodyParts;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the body parts a replay project removed from a player actor or a recorded player, once the renderer has set
 * which parts are visible for the frame.
 *
 * @author vyrriox
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerBodyPartsMixin {
    @Inject(method = "setModelProperties", at = @At("TAIL"))
    private void justreplay$hideParts(AbstractClientPlayer player, CallbackInfo ci) {
        int mask = BodyParts.hidden(player);
        if (mask != 0) {
            BodyParts.apply(((PlayerRenderer) (Object) this).getModel(), mask);
        }
    }
}
