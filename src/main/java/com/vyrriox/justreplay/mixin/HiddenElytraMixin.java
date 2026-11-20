package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elytra worn by an entity whose chestplate the replay project hid are not drawn either (the wings layer of 26.1.2).
 *
 * @author vyrriox
 */
@Mixin(WingsLayer.class)
public abstract class HiddenElytraMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void justreplay$hiddenElytra(PoseStack pose, SubmitNodeCollector collector, int lightCoords, HumanoidRenderState state,
                                         float yRot, float xRot, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity != null && ArmorSlots.hides(entity, EquipmentSlot.CHEST)) {
            ArmorSlots.countSkipped();
            ci.cancel();
        }
    }
}
