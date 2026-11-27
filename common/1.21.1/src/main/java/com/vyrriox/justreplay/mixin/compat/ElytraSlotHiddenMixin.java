package com.vyrriox.justreplay.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.compat.ElytraSlotCompat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elytra Slot draws the elytra of its slot with a layer of its own: it goes with a hidden chestplate, like the vanilla
 * elytra the hidden armor already skips. The layer's {@code render} is a mod method that keeps its name in every
 * namespace; only the class names of its descriptor change, Mojang names in development and on NeoForge, intermediary
 * names on Fabric (read from the jars of Elytra Slot 9.0.2 NeoForge and 9.0.1 Fabric), so both are listed unmapped.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "com.illusivesoulworks.elytraslot.client.ElytraSlotLayer", remap = false)
public abstract class ElytraSlotHiddenMixin {
    @Inject(method = {
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
                    + "Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            "render(Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;ILnet/minecraft/class_1309;FFFFFF)V"},
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$hiddenChest(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity,
                                        float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                                        float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (ElytraSlotCompat.hides(entity)) {
            ci.cancel();
        }
    }
}
