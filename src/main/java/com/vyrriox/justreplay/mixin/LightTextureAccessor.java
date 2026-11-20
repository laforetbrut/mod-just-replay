package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The lightmap's pixels, read by the self test to check night vision. */
@Mixin(LightTexture.class)
public interface LightTextureAccessor {
    @Accessor("lightPixels")
    NativeImage justreplay$lightPixels();

    @Accessor("updateLightTexture")
    void justreplay$setUpdateLightTexture(boolean update);
}
