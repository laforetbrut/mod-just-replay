package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What an entity wears on its head instead of a helmet (carved pumpkin, skull, banner) goes with a hidden helmet.
 *
 * @author vyrriox
 */
@Mixin(CustomHeadLayer.class)
public abstract class HiddenHeadItemMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
            + "Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void justreplay$hiddenHeadItem(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing,
                                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                                           float headPitch, CallbackInfo ci) {
        if (ArmorSlots.hides(entity, EquipmentSlot.HEAD)) {
            ArmorSlots.countSkipped();
            ci.cancel();
        }
    }
}
