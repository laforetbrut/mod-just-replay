package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elytra worn by an entity whose chestplate the replay project hid are not drawn either.
 *
 * @author vyrriox
 */
@Mixin(ElytraLayer.class)
public abstract class HiddenElytraMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
            + "Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void justreplay$hiddenElytra(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing,
                                         float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                                         float headPitch, CallbackInfo ci) {
        if (ArmorSlots.hides(entity, EquipmentSlot.CHEST)) {
            ArmorSlots.countSkipped();
            ci.cancel();
        }
    }
}
