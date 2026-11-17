package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.BodyParts;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An item held by an arm the replay project hid is not drawn floating in the air.
 *
 * @author vyrriox
 */
@Mixin(ItemInHandLayer.class)
public abstract class HiddenArmItemMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void justreplay$hiddenArm(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
                                      PoseStack pose, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        Entity entity = RenderStateEntity.of(state);
        if (entity != null && BodyParts.armHidden(entity, arm)) {
            ci.cancel();
        }
    }
}
