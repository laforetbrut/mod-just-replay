package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
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
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void justreplay$hiddenHeadItem(PoseStack pose, SubmitNodeCollector collector, int lightCoords, LivingEntityRenderState state,
                                           float yRot, float xRot, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity != null && ArmorSlots.hides(entity, EquipmentSlot.HEAD)) {
            ArmorSlots.countSkipped();
            ci.cancel();
        }
    }
}
