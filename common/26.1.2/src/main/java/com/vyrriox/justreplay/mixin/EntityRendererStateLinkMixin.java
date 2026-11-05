package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Links a render state to its entity at the one final method every extraction goes through: world entities and
 * entities drawn in a screen alike.
 *
 * @author vyrriox
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererStateLinkMixin {
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"))
    private void justreplay$link(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        ((RenderStateEntity) (Object) cir.getReturnValue()).justreplay$setEntity(entity);
    }
}
