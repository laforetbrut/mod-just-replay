package com.vyrriox.justreplay.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The lightmap state maker and the fog renderer of the game, read by the self test to check night vision and fog. */
@Mixin(GameRenderer.class)
public interface GameRendererVisualAccessor {
    @Accessor("lightmapRenderStateExtractor")
    LightmapRenderStateExtractor justreplay$lightmapExtractor();

    @Accessor("fogRenderer")
    FogRenderer justreplay$fogRenderer();
}
