package com.vyrriox.justreplay.editor.overlay;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Render type of an overlay image. The render state shards are protected in vanilla and the factory of render types
 * is private, so a subclass holds the shards itself and sets them up in the order vanilla does. No access
 * transformer is needed, which keeps this the same on every loader.
 *
 * @author vyrriox
 */
final class ImageRenderType extends RenderType {
    private ImageRenderType(String name, List<RenderStateShard> shards) {
        super(name, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 256, false, true,
                () -> shards.forEach(RenderStateShard::setupRenderState),
                () -> shards.forEach(RenderStateShard::clearRenderState));
    }

    /**
     * The text shader (fullbright, translucent, understood by shader packs) with a smooth texture filter, depth
     * tested or drawn over everything.
     */
    static RenderType image(ResourceLocation texture, boolean through) {
        // Every shard of a composite state, in vanilla order, with the vanilla defaults for those not used here.
        List<RenderStateShard> shards = List.of(
                new TextureStateShard(texture, true, false),
                through ? RENDERTYPE_TEXT_SEE_THROUGH_SHADER : RENDERTYPE_TEXT_SHADER,
                TRANSLUCENT_TRANSPARENCY,
                through ? NO_DEPTH_TEST : LEQUAL_DEPTH_TEST,
                CULL,
                LIGHTMAP,
                NO_OVERLAY,
                NO_LAYERING,
                MAIN_TARGET,
                DEFAULT_TEXTURING,
                through ? COLOR_WRITE : COLOR_DEPTH_WRITE,
                DEFAULT_LINE,
                NO_COLOR_LOGIC);
        return new ImageRenderType(JustReplay.MOD_ID + "_overlay_image" + (through ? "_see_through" : ""), shards);
    }
}
