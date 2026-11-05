package com.vyrriox.justreplay.forge.mixin;

import com.vyrriox.justreplay.forge.ForgeClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge has no event inside the main pass of the level, whose body is this lambda. These are the two points where the
 * other loaders fire their stages: after the translucent terrain, and at the very end of the pass.
 *
 * @author vyrriox
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererStagesMixin {
    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            ordinal = 1, shift = At.Shift.AFTER))
    private void justreplay$afterTranslucent(CallbackInfo ci) {
        ForgeClient.onAfterTranslucent();
    }

    @Inject(method = "lambda$addMainPass$0", at = @At("RETURN"))
    private void justreplay$afterTranslucentParticles(CallbackInfo ci) {
        ForgeClient.onAfterTranslucentParticles();
    }
}
