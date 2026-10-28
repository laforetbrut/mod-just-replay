package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.EntityEdits;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Outer skin layer (hat, jacket, sleeves, trousers) of replayed players. A player actor is a fresh entity whose
 * model parts default to none: it shows them all unless its settings switch them off. A recorded player keeps what
 * it had in the recording unless its entity edit switches them off.
 *
 * @author vyrriox
 */
@Mixin(Player.class)
public abstract class PlayerSkinLayersMixin {
    @Inject(method = "isModelPartShown", at = @At("HEAD"), cancellable = true)
    private void justreplay$skinLayers(PlayerModelPart part, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ActorManager.isActorEntity(self)) {
            cir.setReturnValue(!ActorManager.hidesSkinLayers(self));
        } else if (self.level().isClientSide() && EntityEdits.hidesSkinLayers(self)) {
            cir.setReturnValue(false);
        }
    }
}
