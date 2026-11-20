package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.Visuals;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Night vision of the World tab: the lightmap state of the frame gets a brightness far past the game's setting, so
 * every block is fully lit with neutral colors (no tint, unlike the night vision effect).
 *
 * @author vyrriox
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class VisualLightMixin {
    @Inject(method = "extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V", at = @At("RETURN"))
    private void justreplay$fullBright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (Visuals.nightVision()) {
            state.brightness = Visuals.FULL_BRIGHT;
        }
    }
}
