package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vyrriox.justreplay.editor.Visuals;
import com.vyrriox.justreplay.editor.WorldSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The fog of the World tab: once the game (and the mods listening to its fog event) set the terrain fog, a replay with
 * reduced or no fog moves its distances. The fog under water, in lava or in powder snow stays.
 *
 * @author vyrriox
 */
@Mixin(FogRenderer.class)
public abstract class VisualFogMixin {
    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V", at = @At("TAIL"))
    private static void justreplay$replayFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog,
                                             float partialTick, CallbackInfo ci) {
        String fog = Visuals.fog();
        if (fog == null || mode != FogRenderer.FogMode.FOG_TERRAIN || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        float[] distances = WorldSettings.fogDistances(fog, RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), renderDistance);
        RenderSystem.setShaderFogStart(distances[0]);
        RenderSystem.setShaderFogEnd(distances[1]);
    }
}
