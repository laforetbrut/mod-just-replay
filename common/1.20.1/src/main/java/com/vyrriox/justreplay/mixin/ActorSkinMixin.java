package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.EntityEdits;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player actors wearing a skin file: the skin and arm model come from the file instead of the player list entry, and
 * the player wears no cape or custom elytra. Recorded players follow the timed skin changes of their entity edit.
 * Every other player keeps its vanilla skin.
 *
 * @author vyrriox
 */
@Mixin(AbstractClientPlayer.class)
public abstract class ActorSkinMixin {
    @Unique
    private ActorManager.FileSkin justreplay$fileSkin() {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        ActorManager.FileSkin skin = ActorManager.customSkin(self);
        return skin != null ? skin : EntityEdits.skinOf(self);
    }

    @Inject(method = "getSkinTextureLocation", at = @At("HEAD"), cancellable = true)
    private void justreplay$skinTexture(CallbackInfoReturnable<ResourceLocation> cir) {
        ActorManager.FileSkin skin = justreplay$fileSkin();
        if (skin != null) {
            cir.setReturnValue(skin.texture());
        }
    }

    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void justreplay$modelName(CallbackInfoReturnable<String> cir) {
        ActorManager.FileSkin skin = justreplay$fileSkin();
        if (skin != null) {
            cir.setReturnValue(skin.slim() ? "slim" : "default");
        }
    }

    @Inject(method = "getCloakTextureLocation", at = @At("HEAD"), cancellable = true)
    private void justreplay$cloak(CallbackInfoReturnable<ResourceLocation> cir) {
        if (justreplay$fileSkin() != null) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getElytraTextureLocation", at = @At("HEAD"), cancellable = true)
    private void justreplay$elytra(CallbackInfoReturnable<ResourceLocation> cir) {
        if (justreplay$fileSkin() != null) {
            cir.setReturnValue(null);
        }
    }
}
