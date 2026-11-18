package com.vyrriox.justreplay.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.compat.MorePlayerModelsCompat;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * More Player Models draws the parts it adds to a player (ears, a tail, wings) one by one, each attached to a body
 * part, without looking at whether that body part is shown. A part attached to a body part the replay project hid is
 * skipped. The three parameters typed by the mod are taken as plain objects. Optional: does nothing when the mod is
 * absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "noppes.mpm.client.layer.LayerParts", remap = false)
public abstract class MorePlayerModelsPartsMixin {
    @Inject(method = "renderPart", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$hiddenPart(@Coerce Object data, @Coerce Object part, PoseStack pose, MultiBufferSource buffers,
                                              int light, AbstractClientPlayer player, PlayerModel<?> model, @Coerce Object look,
                                              CallbackInfo ci) {
        if (MorePlayerModelsCompat.hidesPart(player, part)) {
            ci.cancel();
        }
    }
}
