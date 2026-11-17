package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ActorAnimator;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whole-body motion of custom emotes (the Emotecraft "body" part): moves and turns the pose stack of humanoid actors
 * after the renderer turned them to their body yaw, before the model is drawn. Other entities only pay a map lookup.
 *
 * @author vyrriox
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ActorWholeBodyMixin {
    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void justreplay$wholeBody(LivingEntity entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick,
                                      float scale, CallbackInfo ci) {
        ActorAnimator.applyCustomEmoteBody(entity, poseStack);
    }
}
