package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.ActorAnimator;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sitting, arm gestures, dances and custom emotes of humanoid actors. Vanilla has no pose for them without a vehicle, so the model
 * is adjusted for actor entities only; every other entity is left untouched. The armor models take the same state,
 * so they get the same pose.
 *
 * @author vyrriox
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Shadow
    @Final
    public ModelPart rightArm;
    @Shadow
    @Final
    public ModelPart leftArm;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("HEAD"))
    private void justreplay$sit(HumanoidRenderState state, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity != null && ActorAnimator.isSitting(entity)) {
            state.isPassenger = true;
        }
    }

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void justreplay$gesture(HumanoidRenderState state, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity == null) {
            return;
        }
        ActorAnimator.applyGesture(entity, state.ageInTicks, ((HumanoidModel<?>) (Object) this).head, rightArm, leftArm);
        ActorAnimator.applyDance(entity, (HumanoidModel<?>) (Object) this);
        ActorAnimator.applyCustomEmote(entity, (HumanoidModel<?>) (Object) this);
    }
}
