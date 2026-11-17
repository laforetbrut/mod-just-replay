package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla cameras have no roll on this version, and the other loaders patch one into their camera set-up. The roll is
 * applied last, about the view direction, so the view matrix, the frustum and the particles all follow it. A mirrored
 * camera rolls the other way and a camera in a bed does not roll, as on those loaders.
 *
 * @author vyrriox
 */
@Mixin(Camera.class)
public abstract class CameraRollMixin {
    private static final float JUSTREPLAY_TO_RADIANS = (float) (Math.PI / 180.0);

    @Shadow
    private Quaternionf rotation;

    @Shadow
    private Vector3f forwards;

    @Shadow
    private Vector3f up;

    @Shadow
    private Vector3f left;

    @Inject(method = "setup", at = @At("RETURN"))
    private void justreplay$roll(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        Float roll = ClientHooks.cameraRoll();
        if (roll == null || !detached && entity instanceof LivingEntity living && living.isSleeping()) {
            return;
        }
        float applied = detached && mirrored ? -roll : roll;
        this.rotation.rotateZ(-applied * JUSTREPLAY_TO_RADIANS);
        this.forwards.set(0.0F, 0.0F, 1.0F).rotate(this.rotation);
        this.up.set(0.0F, 1.0F, 0.0F).rotate(this.rotation);
        this.left.set(1.0F, 0.0F, 0.0F).rotate(this.rotation);
    }
}
