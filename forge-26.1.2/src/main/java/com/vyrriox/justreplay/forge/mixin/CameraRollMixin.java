package com.vyrriox.justreplay.forge.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.forge.ForgeClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Camera roll. Forge's angles event fires after the camera state was extracted for the frame, too late for the view
 * matrix and the frustum, so the roll goes in at the end of the alignment. A roll turns around the forward axis, so the
 * third person distance measured before stays right. A mirrored camera rolls the other way, and neither a camera in a
 * bed nor one on a smoothed minecart rolls, as on the other loaders.
 *
 * @author vyrriox
 */
@Mixin(Camera.class)
public abstract class CameraRollMixin {
    @Shadow
    private float xRot;
    @Shadow
    private float yRot;
    @Shadow
    private Entity entity;
    @Shadow
    private boolean detached;

    @Shadow
    public abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void justreplay$roll(float partialTicks, CallbackInfo ci) {
        Float roll = ClientHooks.cameraRoll();
        if (roll == null || !this.detached && this.entity instanceof LivingEntity living && living.isSleeping()
                || this.entity.isPassenger() && this.entity.getVehicle() instanceof Minecart minecart
                && minecart.getBehavior() instanceof NewMinecartBehavior behavior && behavior.cartHasPosRotLerp()) {
            ForgeClient.setCameraRoll(0.0F);
            return;
        }
        // Forge's setRotation applies the roll with the sign opposite to Fabric's rebuild and NeoForge's event.
        float applied = this.detached && Minecraft.getInstance().options.getCameraType().isMirrored() ? roll : -roll;
        this.setRotation(this.yRot, this.xRot, applied);
        ForgeClient.setCameraRoll(applied);
    }
}
