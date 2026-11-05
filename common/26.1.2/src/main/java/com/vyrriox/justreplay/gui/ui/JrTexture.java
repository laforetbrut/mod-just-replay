package com.vyrriox.justreplay.gui.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Dynamic texture with a chosen sampler. The 1.21.1 setFilter call became a sampler picked at creation time.
 *
 * @author vyrriox
 */
public final class JrTexture extends DynamicTexture {
    public JrTexture(String label, NativeImage image, boolean linear) {
        super(() -> label, image);
        FilterMode mode = linear ? FilterMode.LINEAR : FilterMode.NEAREST;
        this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, mode, mode, false);
    }
}
