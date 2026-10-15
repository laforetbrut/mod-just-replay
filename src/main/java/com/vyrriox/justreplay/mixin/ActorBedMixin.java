package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.ActorManager;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sleeping actors have no bed of their own (a sleeping position would make the client wake them and edit the bed
 * block), so the renderer would always lay them the same way. Their bed orientation is the way they face: the
 * head lies where the actor stands, the body behind it, like a vanilla sleeper whose head is on the pillow.
 *
 * @author vyrriox
 */
@Mixin(LivingEntity.class)
public abstract class ActorBedMixin {
    @Inject(method = "getBedOrientation", at = @At("HEAD"), cancellable = true)
    private void justreplay$actorBed(CallbackInfoReturnable<Direction> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (ActorManager.isActorEntity(self) && self.hasPose(Pose.SLEEPING) && self.getSleepingPos().isEmpty()) {
            cir.setReturnValue(Direction.fromYRot(self.getYRot()));
        }
    }
}
