package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.client.Frames;
import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a frozen level on its last tick: without this, what stopped ticking would shake with the partial tick. Players
 * still tick and get the real partial tick. The render tick counter (clouds, rain) holds too.
 *
 * @author vyrriox
 */
@Mixin(LevelRenderer.class)
public abstract class FreezeLevelRendererMixin {
    @Shadow
    private int ticks;

    @Shadow
    private void renderEntity(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource) {
        throw new AssertionError();
    }

    /** The method raises the counter first, so this cancels that. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void justreplay$holdTicks(CallbackInfo ci) {
        if (TimeControl.isFrozen()) {
            this.ticks--;
        }
    }

    @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float justreplay$frozenPartialTick(float partialTick) {
        return TimeControl.isFrozen() ? 1.0F : partialTick;
    }

    /** Runs again with the partial tick the entity needs; the second run finds it right and goes through. */
    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void justreplay$entityPartialTick(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack,
                                              MultiBufferSource bufferSource, CallbackInfo ci) {
        if (!TimeControl.isFrozen() || TimeControl.isEntityFrozen(entity)) {
            return;
        }
        float real = Frames.realPartialTick();
        if (partialTick != real) {
            ci.cancel();
            renderEntity(entity, camX, camY, camZ, real, poseStack, bufferSource);
        }
    }
}
