package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.Visuals;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Night vision of the World tab: the lightmap reads a brightness far past the game's setting, so every block is fully
 * lit with neutral colors (no tint, unlike the night vision effect). The second option read of the lightmap update is
 * the brightness; the first is the darkness effect scale.
 *
 * @author vyrriox
 */
@Mixin(LightTexture.class)
public abstract class VisualLightMixin {
    @Redirect(method = "updateLightTexture(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1))
    private Object justreplay$fullBright(OptionInstance<?> option) {
        return Visuals.nightVision() ? Double.valueOf(Visuals.FULL_BRIGHT) : option.get();
    }
}
