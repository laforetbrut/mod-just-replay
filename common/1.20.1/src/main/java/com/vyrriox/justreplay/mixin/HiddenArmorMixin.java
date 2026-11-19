package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor a replay project hid is not drawn: the piece of a hidden slot is skipped, the entity keeps its equipment.
 * Optional, a mod that reworks armor rendering only loses the option, never the game.
 *
 * @author vyrriox
 */
@Mixin(value = HumanoidArmorLayer.class, priority = 900)
public abstract class HiddenArmorMixin {
    /**
     * The piece drawing method, the same on Minecraft, Forge, NeoForge and Fabric in 1.20.1. The lower priority runs
     * this check before armor renderers that other mods hook at the same place.
     */
    @Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void justreplay$hiddenPiece(PoseStack pose, MultiBufferSource buffers, LivingEntity entity, EquipmentSlot slot,
                                        int light, HumanoidModel<?> model, CallbackInfo ci) {
        if (ArmorSlots.hides(entity, slot)) {
            ArmorSlots.countArmorLayerSkipped();
            ci.cancel();
        }
    }
}
