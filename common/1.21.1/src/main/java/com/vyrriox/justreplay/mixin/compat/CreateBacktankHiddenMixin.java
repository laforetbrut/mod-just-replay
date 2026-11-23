package com.vyrriox.justreplay.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.compat.CreateCompat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create draws the tank of a backtank worn in the chest slot (and of the items that extend it, such as the jetpack of
 * Create Jetpack) with a layer of its own, from the item the entity wears: it goes with a hidden chestplate, like the
 * straps the vanilla armor layer draws. Read from the jar of Create 6.0.10 (NeoForge 1.21.1).
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.equipment.armor.BacktankArmorLayer", remap = false)
public abstract class CreateBacktankHiddenMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
            + "Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$hiddenChest(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity,
                                        float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                                        float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (CreateCompat.hidesBacktank(entity)) {
            ci.cancel();
        }
    }
}
