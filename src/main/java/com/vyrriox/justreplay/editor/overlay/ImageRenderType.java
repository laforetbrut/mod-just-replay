package com.vyrriox.justreplay.editor.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Render type of an overlay image: the vanilla text pipelines with their texture read through a smooth sampler.
 *
 * @author vyrriox
 */
final class ImageRenderType {
    private ImageRenderType() {
    }

    /**
     * The text shader (fullbright, translucent, understood by shader packs) with a smooth texture filter, depth
     * tested or drawn over everything.
     */
    static RenderType image(Identifier texture, boolean through) {
        return RenderType.create(JustReplay.MOD_ID + "_overlay_image" + (through ? "_see_through" : ""),
                RenderSetup.builder(through ? RenderPipelines.TEXT_SEE_THROUGH : RenderPipelines.TEXT)
                        .withTexture("Sampler0", texture, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
    }
}
