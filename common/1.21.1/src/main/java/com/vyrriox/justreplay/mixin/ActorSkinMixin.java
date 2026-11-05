package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.ActorManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player actors wearing a skin file: the skin and arm model come from the file instead of the player list entry.
 * Recorded players follow the timed skin changes of their entity edit. Every other player keeps its vanilla skin.
 *
 * @author vyrriox
 */
@Mixin(AbstractClientPlayer.class)
public abstract class ActorSkinMixin {
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void justreplay$actorSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = ActorManager.customSkin((AbstractClientPlayer) (Object) this);
        if (skin == null) {
            skin = com.vyrriox.justreplay.editor.EntityEdits.skinOf((AbstractClientPlayer) (Object) this);
        }
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }
}
