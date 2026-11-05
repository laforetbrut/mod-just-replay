package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.BodyParts;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the body parts a replay project removed from a player actor or a recorded player, once the model has set
 * which parts are visible for the state. Armor is drawn with player models too: it runs this as well and is left
 * alone, like on versions where only the renderer's own model is hidden.
 *
 * @author vyrriox
 */
@Mixin(PlayerModel.class)
public abstract class PlayerBodyPartsMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void justreplay$hideParts(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel self = (PlayerModel) (Object) this;
        // The model is shared by all players and vanilla sets every other part's visibility itself, never the head's.
        self.head.visible = true;
        if (!(RenderStateEntity.of(state) instanceof AbstractClientPlayer player)) {
            return;
        }
        int mask = BodyParts.hidden(player);
        if (mask != 0 && Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player).getModel() == self) {
            BodyParts.apply(self, mask);
        }
    }
}
