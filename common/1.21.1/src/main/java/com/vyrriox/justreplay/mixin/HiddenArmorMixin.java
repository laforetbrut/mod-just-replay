package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor a replay project hid is not drawn: the piece of a hidden slot is skipped, the entity keeps its equipment.
 * Both hooks are optional, a mod that reworks armor rendering only loses the option, never the game.
 *
 * @author vyrriox
 */
@Mixin(value = HumanoidArmorLayer.class, priority = 900)
public abstract class HiddenArmorMixin {
    /**
     * The piece drawing method of Minecraft, Forge and Fabric. The lower priority runs this check before armor renderers
     * that other mods hook at the same place.
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

    /**
     * NeoForge draws the pieces from an overload taking the animation arguments too: the stack it reads comes back
     * empty for a hidden slot, which skips the piece the same way. Matches every overload by name.
     */
    @Redirect(method = "renderArmorPiece", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack justreplay$hiddenStack(LivingEntity entity, EquipmentSlot slot) {
        if (ArmorSlots.hides(entity, slot)) {
            ArmorSlots.countArmorLayerSkipped();
            return ItemStack.EMPTY;
        }
        return entity.getItemBySlot(slot);
    }
}
