package com.vyrriox.justreplay.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.compat.MorePlayerModelsCompat;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The base of the layers More Player Models draws on a player. The item it shows on the back is attached to the body:
 * it is not drawn on a player whose body the replay project hid. Optional: does nothing when the mod is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "noppes.mpm.client.layer.LayerInterface", remap = false)
public abstract class MorePlayerModelsLayerMixin {
    /** The mod's own typed method, named the same in a development run and in the released jar. */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
            + "Lnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V", at = @At("HEAD"), cancellable = true, require = 0,
            remap = false)
    private void justreplay$hiddenBody(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                                       float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (MorePlayerModelsCompat.hidesLayer(this, player)) {
            ci.cancel();
        }
    }
}
