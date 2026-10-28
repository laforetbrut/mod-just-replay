package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.EntityEdits;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Edits of recorded entities at draw time: a hidden entity is skipped whole (model, name tag, shadow, fire,
 * leash, held items); a moved one is drawn again at its offset and turned, its rotations restored right after,
 * so its physics and interpolation never change.
 *
 * @author vyrriox
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityEditRenderMixin {
    @Unique
    private static boolean justreplay$redrawing;

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void justreplay$editedCulling(E entity, Frustum frustum, double camX, double camY, double camZ,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (EntityEdits.editOf(entity) == null) {
            return;
        }
        if (EntityEdits.isHidden(entity)) {
            // Skipped here, before the renderer is even asked: count it now, render() never runs for it.
            EntityEdits.countSkipped();
            cir.setReturnValue(false);
        } else if (EntityEdits.offset(entity) != null) {
            // Drawn away from its own box: the frustum test of the real position does not apply.
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void justreplay$editedRender(E entity, double x, double y, double z, float rotationYaw, float partialTicks,
                                                           PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (justreplay$redrawing || EntityEdits.editOf(entity) == null) {
            return;
        }
        if (EntityEdits.isHidden(entity)) {
            EntityEdits.countSkipped();
            ci.cancel();
            return;
        }
        double[] o = EntityEdits.offset(entity);
        if (o == null) {
            return;
        }
        ci.cancel();
        EntityEdits.countOffset();
        float turn = (float) o[3];
        float yRot = entity.getYRot();
        float yRotO = entity.yRotO;
        LivingEntity living = entity instanceof LivingEntity l ? l : null;
        float body = 0.0F;
        float bodyO = 0.0F;
        float head = 0.0F;
        float headO = 0.0F;
        if (living != null) {
            body = living.yBodyRot;
            bodyO = living.yBodyRotO;
            head = living.yHeadRot;
            headO = living.yHeadRotO;
            living.yBodyRot += turn;
            living.yBodyRotO += turn;
            living.yHeadRot += turn;
            living.yHeadRotO += turn;
        }
        entity.setYRot(yRot + turn);
        entity.yRotO = yRotO + turn;
        justreplay$redrawing = true;
        try {
            ((EntityRenderDispatcher) (Object) this).render(entity, x + o[0], y + o[1], z + o[2], rotationYaw + turn, partialTicks,
                    poseStack, buffer, packedLight);
        } finally {
            justreplay$redrawing = false;
            entity.setYRot(yRot);
            entity.yRotO = yRotO;
            if (living != null) {
                living.yBodyRot = body;
                living.yBodyRotO = bodyO;
                living.yHeadRot = head;
                living.yHeadRotO = headO;
            }
        }
    }
}
