package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor a replay project hid is not drawn: the piece of a hidden slot is skipped, the entity keeps its equipment and
 * its render state keeps the stack. Optional, a mod that reworks armor rendering only loses the option, never the game.
 *
 * @author vyrriox
 */
@Mixin(value = HumanoidArmorLayer.class, priority = 900)
public abstract class HiddenArmorMixin {
    /**
     * The piece submitting method, the same on Minecraft, Forge, NeoForge and Fabric in 26.1.2. The lower priority runs
     * this check before armor renderers that other mods hook at the same place, like the one of Fabric API.
     */
    @Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;I"
            + "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void justreplay$hiddenPiece(PoseStack pose, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot,
                                        int lightCoords, HumanoidRenderState state, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity != null && ArmorSlots.hides(entity, slot)) {
            ArmorSlots.countArmorLayerSkipped();
            ci.cancel();
        }
    }
}
