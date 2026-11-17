package com.vyrriox.justreplay.fabric.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Camera roll and FOV, which vanilla has no hook for. Rebuilding the rotation at the end of the alignment reaches the
 * view matrix, the frustum and the particles alike; a roll turns around the forward axis, so the third person distance
 * measured before stays right. A mirrored camera rolls the other way, and neither a camera in a bed nor one on a
 * smoothed minecart rolls, as on the other loaders.
 *
 * @author vyrriox
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
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
    @Shadow
    private Entity entity;
    @Shadow
    private boolean detached;
    @Shadow
    private int matrixPropertiesDirty;
    @Shadow
    private boolean isPanoramicMode;

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void justreplay$roll(float partialTicks, CallbackInfo ci) {
        Float roll = ClientHooks.cameraRoll();
        if (roll == null || !this.detached && this.entity instanceof LivingEntity living && living.isSleeping()
                || this.entity.isPassenger() && this.entity.getVehicle() instanceof Minecart minecart
                && minecart.getBehavior() instanceof NewMinecartBehavior behavior && behavior.cartHasPosRotLerp()) {
            return;
        }
        float applied = this.detached && Minecraft.getInstance().options.getCameraType().isMirrored() ? -roll : roll;
        this.rotation.rotationYXZ((float) Math.PI - this.yRot * justreplay$TO_RADIANS, -this.xRot * justreplay$TO_RADIANS, -applied * justreplay$TO_RADIANS);
        FORWARDS.rotate(this.rotation, this.forwards);
        UP.rotate(this.rotation, this.up);
        LEFT.rotate(this.rotation, this.left);
        this.matrixPropertiesDirty |= 3;
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void justreplay$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!this.isPanoramicMode) {
            justreplay$applyFov(true, cir);
        }
    }

    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true)
    private void justreplay$hudFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        justreplay$applyFov(false, cir);
    }

    @Unique
    private static void justreplay$applyFov(boolean usedConfiguredFov, CallbackInfoReturnable<Float> cir) {
        Double fov = ClientHooks.cameraFov(usedConfiguredFov);
        if (fov != null) {
            cir.setReturnValue(fov.floatValue());
        }
    }
}
