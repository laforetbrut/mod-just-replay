package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides a name tag once the state is extracted, since subclasses override both the name check and the drawing, and
 * the renderer draws whatever text the state holds.
 *
 * @author vyrriox
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNameTagMixin {
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"))
    private void justreplay$nameTag(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        if (ClientHooks.hidesNameTag(entity)) {
            EntityRenderState state = cir.getReturnValue();
            state.nameTag = null;
            state.scoreText = null;
        }
    }
}
