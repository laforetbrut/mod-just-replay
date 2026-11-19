package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Epic Fight draws the armor of the entities it animates with its own layer, which reads each armor slot itself and
 * never reaches the vanilla armor piece hook. The stack it reads for the slots a replay project hid comes back empty,
 * which skips the piece; the entity keeps its equipment. Elytra and head items go through the vanilla layers there and
 * stay covered by their own hooks. Optional: does nothing without Epic Fight.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.client.renderer.patched.layer.WearableItemLayer", remap = false)
public abstract class EpicFightArmorLayerMixin {
    @Redirect(method = "renderLayer", require = 0, remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;", remap = true))
    private ItemStack justreplay$hiddenStack(LivingEntity entity, EquipmentSlot slot) {
        if (ArmorSlots.hides(entity, slot)) {
            ArmorSlots.countSkipped();
            return ItemStack.EMPTY;
        }
        return entity.getItemBySlot(slot);
    }
}
