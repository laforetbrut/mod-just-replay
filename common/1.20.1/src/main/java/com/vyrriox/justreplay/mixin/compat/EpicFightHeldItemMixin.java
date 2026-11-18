package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.editor.BodyParts;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Epic Fight draws the held items of the entities it animates itself, without the vanilla arm item hook: the item of
 * an arm the replay project hid comes back empty, so it is not drawn floating in the air. Optional: does nothing
 * without Epic Fight.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer", remap = false)
public abstract class EpicFightHeldItemMixin {
    @Redirect(method = "renderLayer", require = 0, remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getMainHandItem()Lnet/minecraft/world/item/ItemStack;", remap = true))
    private ItemStack justreplay$hiddenMainHand(LivingEntity entity) {
        return BodyParts.armHidden(entity, entity.getMainArm()) ? ItemStack.EMPTY : entity.getMainHandItem();
    }

    @Redirect(method = "renderLayer", require = 0, remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getOffhandItem()Lnet/minecraft/world/item/ItemStack;", remap = true))
    private ItemStack justreplay$hiddenOffHand(LivingEntity entity) {
        HumanoidArm off = entity.getMainArm() == HumanoidArm.LEFT ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        return BodyParts.armHidden(entity, off) ? ItemStack.EMPTY : entity.getOffhandItem();
    }
}
