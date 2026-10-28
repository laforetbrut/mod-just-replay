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
 * A scaled entity (recorded or actor) is drawn inside a pose scaled around its feet. Entities without an edit
 * pay one id compare and one map lookup that bails out when the project has no entity edits.
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
        com.vyrriox.justreplay.editor.ReplayProject.EntityEdit edit = EntityEdits.editOf(entity);
        if (edit == null) {
            return;
        }
        if (EntityEdits.isHidden(entity)) {
            // Skipped here, before the renderer is even asked: count it now, render() never runs for it.
            EntityEdits.countSkipped();
            cir.setReturnValue(false);
        } else if (edit.scale > 1.001F || EntityEdits.offset(entity) != null) {
            // Drawn away from its own box: the frustum test of the real position does not apply.
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void justreplay$editedRender(E entity, double x, double y, double z, float rotationYaw, float partialTicks,
                                                           PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (justreplay$redrawing) {
            return;
        }
        if (com.vyrriox.justreplay.editor.ActorManager.isActorEntity(entity)) {
            float actorScale = com.vyrriox.justreplay.editor.ActorManager.renderScale(entity);
            if (Math.abs(actorScale - 1.0F) >= 0.001F) {
                ci.cancel();
                justreplay$drawScaled(entity, x, y, z, rotationYaw, partialTicks, poseStack, buffer, packedLight, actorScale);
            }
            return;
        }
        com.vyrriox.justreplay.editor.ReplayProject.EntityEdit edit = EntityEdits.editOf(entity);
        if (edit == null) {
            return;
        }
        if (EntityEdits.isHidden(entity)) {
            EntityEdits.countSkipped();
            ci.cancel();
            return;
        }
        double[] o = EntityEdits.offset(entity);
        float scale = edit.scale;
        boolean scaled = Math.abs(scale - 1.0F) >= 0.001F;
        if (o == null) {
            if (scaled) {
                ci.cancel();
                justreplay$drawScaled(entity, x, y, z, rotationYaw, partialTicks, poseStack, buffer, packedLight, scale);
            }
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
            if (scaled) {
                justreplay$drawScaled(entity, x + o[0], y + o[1], z + o[2], rotationYaw + turn, partialTicks, poseStack, buffer,
                        packedLight, scale);
            } else {
                ((EntityRenderDispatcher) (Object) this).render(entity, x + o[0], y + o[1], z + o[2], rotationYaw + turn, partialTicks,
                        poseStack, buffer, packedLight);
            }
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

    /**
     * Draws the entity scaled uniformly around its feet: the pose is moved to the feet and scaled, then the vanilla
     * render runs at the origin of that pose, so the model, name tag, shadow and fire all follow the scale.
     */
    @Unique
    private <E extends Entity> void justreplay$drawScaled(E entity, double x, double y, double z, float rotationYaw, float partialTicks,
                                                        PoseStack poseStack, MultiBufferSource buffer, int packedLight, float scale) {
        boolean was = justreplay$redrawing;
        justreplay$redrawing = true;
        poseStack.pushPose();
        try {
            poseStack.translate(x, y, z);
            poseStack.scale(scale, scale, scale);
            ((EntityRenderDispatcher) (Object) this).render(entity, 0.0, 0.0, 0.0, rotationYaw, partialTicks, poseStack, buffer, packedLight);
        } finally {
            poseStack.popPose();
            justreplay$redrawing = was;
        }
    }
}
