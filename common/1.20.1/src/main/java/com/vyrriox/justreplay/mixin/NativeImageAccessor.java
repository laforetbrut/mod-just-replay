package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Address of a picture's pixels, so an export frame is copied once from it instead of through two arrays.
 *
 * @author vyrriox
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    @Accessor("pixels")
    long justreplay$pixels();
}
