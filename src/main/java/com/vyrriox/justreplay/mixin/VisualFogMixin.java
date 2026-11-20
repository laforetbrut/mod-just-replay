package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.Visuals;
import com.vyrriox.justreplay.editor.WorldSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The fog of the World tab: once the game (and the mods listening to its fog event) computed the fog of the frame, a
 * replay with reduced or no fog moves its distances before they reach the GPU. The fog under water, in lava or in
 * powder snow stays.
 *
 * @author vyrriox
 */
@Mixin(FogRenderer.class)
public abstract class VisualFogMixin {
    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
            at = @At("RETURN"))
    private void justreplay$replayFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkness, ClientLevel level,
                                      CallbackInfoReturnable<FogData> cir) {
        String fog = Visuals.fog();
        FogData data = cir.getReturnValue();
        if (fog == null || data == null || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        float view = data.renderDistanceEnd;
        float[] environmental = WorldSettings.fogDistances(fog, data.environmentalStart, data.environmentalEnd, view);
        float[] distance = WorldSettings.fogDistances(fog, data.renderDistanceStart, data.renderDistanceEnd, view);
        data.environmentalStart = environmental[0];
        data.environmentalEnd = environmental[1];
        data.renderDistanceStart = distance[0];
        data.renderDistanceEnd = distance[1];
    }
}
