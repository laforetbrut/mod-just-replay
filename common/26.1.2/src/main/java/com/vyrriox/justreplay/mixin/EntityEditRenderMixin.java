package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.editor.EntityEdits;
import com.vyrriox.justreplay.editor.RenderStateEntity;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
 * leash, held items); a moved one is submitted at its offset, and turned for its extraction with its rotations
 * restored right after, so its physics and interpolation never change.
 * A scaled entity (recorded or actor) is submitted inside a pose scaled around its feet. Entities without an edit
 * pay one id compare and one map lookup that bails out when the project has no entity edits.
 *
 * @author vyrriox
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityEditRenderMixin {
    @Unique
    private static boolean justreplay$redrawing;
    /** Rotations of the entity being extracted turned: yaw, previous yaw, body, previous body, head, previous head. */
    @Unique
    private static final float[] justreplay$saved = new float[6];
    @Unique
    private static Entity justreplay$turned;

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void justreplay$editedCulling(E entity, Frustum frustum, double camX, double camY, double camZ,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof net.minecraft.world.entity.player.Player player && player.isSpectator()
                && com.vyrriox.justreplay.replay.ReplaySession.isRunning()) {
            // Players in spectator mode were invisible to everyone else in the game; the replay camera, itself a
            // spectator, would show them.
            cir.setReturnValue(false);
            return;
        }
        if (com.vyrriox.justreplay.editor.ActorManager.renderScale(entity) > 1.001F) {
            // A scaled-up actor is drawn well outside its own box: skip the frustum test.
            cir.setReturnValue(true);
            return;
        }
        if (ModelRenderer.isModelActor(entity.getId())) {
            // A model is drawn well outside the box of its armor stand: only the distance check applies.
            cir.setReturnValue(entity.shouldRender(camX, camY, camZ));
            return;
        }
        com.vyrriox.justreplay.editor.ReplayProject.EntityEdit edit = EntityEdits.editOf(entity);
        if (edit == null) {
            return;
        }
        if (EntityEdits.isHidden(entity)) {
            // Skipped here, before the renderer is even asked: count it now, submit() never runs for it.
            EntityEdits.countSkipped();
            cir.setReturnValue(false);
        } else if (edit.scale > 1.001F || EntityEdits.offset(entity) != null) {
            // Drawn away from its own box: the frustum test of the real position does not apply.
            cir.setReturnValue(true);
        }
    }

    /** A turn is read by the extraction: every renderer takes its own rotation fields from the entity there. */
    @Inject(method = "extractEntity", at = @At("HEAD"))
    private <E extends Entity> void justreplay$turnForExtract(E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        double[] o = EntityEdits.offset(entity);
        if (o == null) {
            return;
        }
        float turn = (float) o[3];
        justreplay$turned = entity;
        justreplay$saved[0] = entity.getYRot();
        justreplay$saved[1] = entity.yRotO;
        entity.setYRot(justreplay$saved[0] + turn);
        entity.yRotO = justreplay$saved[1] + turn;
        if (entity instanceof LivingEntity living) {
            justreplay$saved[2] = living.yBodyRot;
            justreplay$saved[3] = living.yBodyRotO;
            justreplay$saved[4] = living.yHeadRot;
            justreplay$saved[5] = living.yHeadRotO;
            living.yBodyRot += turn;
            living.yBodyRotO += turn;
            living.yHeadRot += turn;
            living.yHeadRotO += turn;
        }
    }

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void justreplay$restoreAfterExtract(E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        if (justreplay$turned != entity) {
            return;
        }
        justreplay$turned = null;
        entity.setYRot(justreplay$saved[0]);
        entity.yRotO = justreplay$saved[1];
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = justreplay$saved[2];
            living.yBodyRotO = justreplay$saved[3];
            living.yHeadRot = justreplay$saved[4];
            living.yHeadRotO = justreplay$saved[5];
        }
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private <S extends EntityRenderState> void justreplay$editedSubmit(S state, CameraRenderState camera, double x, double y, double z,
                                                                       PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
        if (justreplay$redrawing) {
            return;
        }
        Entity entity = RenderStateEntity.of(state);
        if (entity == null) {
            return;
        }
        if (com.vyrriox.justreplay.editor.ActorManager.isActorEntity(entity)) {
            float actorScale = com.vyrriox.justreplay.editor.ActorManager.renderScale(entity);
            if (Math.abs(actorScale - 1.0F) >= 0.001F) {
                ci.cancel();
                justreplay$submitScaled(state, camera, x, y, z, poseStack, collector, actorScale);
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
                justreplay$submitScaled(state, camera, x, y, z, poseStack, collector, scale);
            }
            return;
        }
        ci.cancel();
        EntityEdits.countOffset();
        justreplay$redrawing = true;
        try {
            if (scaled) {
                justreplay$submitScaled(state, camera, x + o[0], y + o[1], z + o[2], poseStack, collector, scale);
            } else {
                ((EntityRenderDispatcher) (Object) this).submit(state, camera, x + o[0], y + o[1], z + o[2], poseStack, collector);
            }
        } finally {
            justreplay$redrawing = false;
        }
    }

    /**
     * Submits the entity scaled uniformly around its feet: the pose is moved to the feet and scaled, then the vanilla
     * submission runs at the origin of that pose, so the model, name tag, shadow and fire all follow the scale.
     */
    @Unique
    private <S extends EntityRenderState> void justreplay$submitScaled(S state, CameraRenderState camera, double x, double y, double z,
                                                                       PoseStack poseStack, SubmitNodeCollector collector, float scale) {
        boolean was = justreplay$redrawing;
        justreplay$redrawing = true;
        poseStack.pushPose();
        try {
            poseStack.translate(x, y, z);
            poseStack.scale(scale, scale, scale);
            ((EntityRenderDispatcher) (Object) this).submit(state, camera, 0.0, 0.0, 0.0, poseStack, collector);
        } finally {
            poseStack.popPose();
            justreplay$redrawing = was;
        }
    }
}
