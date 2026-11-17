package com.vyrriox.justreplay.gui.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Dynamic texture with a chosen filter. The 1.21.1 setFilter call became a sampler picked at creation time. Nearest
 * textures keep the default repeating sampler, as models may wrap their UVs; linear ones (interface shapes, icons,
 * LUTs) clamp, so a sample on the border never reads the opposite edge.
 *
 * @author vyrriox
 */
public final class JrTexture extends DynamicTexture {
    public JrTexture(String label, NativeImage image, boolean linear) {
        super(() -> label, image);
        if (linear) {
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
