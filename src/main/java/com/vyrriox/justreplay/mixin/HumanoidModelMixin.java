package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.ActorAnimator;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sitting, arm gestures and dances of humanoid actors. Vanilla has no pose for them without a vehicle, so the model
 * is adjusted for actor entities only; every other entity is left untouched.
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

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void justreplay$sit(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                                float headPitch, CallbackInfo ci) {
        if (ActorAnimator.isSitting(entity)) {
            ((HumanoidModel<?>) (Object) this).riding = true;
        }
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void justreplay$gesture(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                                    float headPitch, CallbackInfo ci) {
        ActorAnimator.applyGesture(entity, ageInTicks, rightArm, leftArm);
        ActorAnimator.applyDance(entity, (HumanoidModel<?>) (Object) this);
    }
}
