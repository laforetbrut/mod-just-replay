package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.ActorAnimator;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
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
    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
            at = @At("TAIL"))
    private void justreplay$wholeBody(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity != null) {
            ActorAnimator.applyCustomEmoteBody(entity, poseStack);
        }
    }
}
