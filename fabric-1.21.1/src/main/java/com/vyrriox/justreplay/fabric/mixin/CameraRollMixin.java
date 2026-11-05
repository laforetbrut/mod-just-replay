package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla cameras have no roll. Rebuilding the rotation at the end of the set-up reaches the view matrix, the frustum
 * and the particles alike, as the roll argument the other loaders patch in does. A mirrored camera rolls the other way
 * and a camera in a bed does not roll, as on those loaders.
 *
 * @author vyrriox
 */
@Mixin(Camera.class)
public abstract class CameraRollMixin {
    @Unique
    private static final float justreplay$TO_RADIANS = (float) (Math.PI / 180.0);

    @Shadow
    @Final
    private static Vector3f FORWARDS;
    @Shadow
    @Final
    private static Vector3f UP;
    @Shadow
    @Final
    private static Vector3f LEFT;
    @Shadow
    @Final
    private Vector3f forwards;
    @Shadow
    @Final
    private Vector3f up;
    @Shadow
    @Final
    private Vector3f left;
    @Shadow
    @Final
    private Quaternionf rotation;
    @Shadow
    private float xRot;
    @Shadow
    private float yRot;

    @Inject(method = "setup", at = @At("RETURN"))
    private void justreplay$roll(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        Float roll = ClientHooks.cameraRoll();
        if (roll == null || !detached && entity instanceof LivingEntity living && living.isSleeping()) {
            return;
        }
        float applied = detached && mirrored ? -roll : roll;
        this.rotation.rotationYXZ((float) Math.PI - this.yRot * justreplay$TO_RADIANS, -this.xRot * justreplay$TO_RADIANS, -applied * justreplay$TO_RADIANS);
        FORWARDS.rotate(this.rotation, this.forwards);
        UP.rotate(this.rotation, this.up);
        LEFT.rotate(this.rotation, this.left);
    }
}
