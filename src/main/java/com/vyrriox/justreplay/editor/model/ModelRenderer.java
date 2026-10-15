package com.vyrriox.justreplay.editor.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ActorAnimator;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws Blockbench model actors. A model actor is an invisible client side armor stand; its vanilla render is
 * replaced by the model inside the entity render pass, so world lighting, shader packs (their shadow pass
 * included) and video exports treat it like any other entity.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class ModelRenderer {
    private static final Map<Integer, ReplayProject.ActorData> ACTORS = new HashMap<>();

    /** Corners of each face (top left, top right, bottom right, bottom left), as 0 = from and 1 = to per axis. */
    private static final int[][][] CORNERS = {
            {{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}}, // north
            {{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}}, // east
            {{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}}, // south
            {{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}}, // west
            {{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, // up
            {{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}}, // down
    };
    private static final float[][] NORMALS = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    /** Counter-clockwise from outside: top left, bottom left, bottom right, top right. */
    private static final int[] ORDER = {0, 3, 2, 1};

    private ModelRenderer() {
    }

    public static void register(int entityId, ReplayProject.ActorData actor) {
        ACTORS.put(entityId, actor);
    }

    public static void unregister(int entityId) {
        ACTORS.remove(entityId);
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        ReplayProject.ActorData actor = ACTORS.get(entity.getId());
        if (actor == null || entity.getId() > -1000) {
            return;
        }
        // The armor stand itself is never drawn, model or not.
        event.setCanceled(true);
        ReplaySession session = ReplaySession.current();
        if (session == null) {
            return;
        }
        ModelLibrary.Loaded loaded = ModelLibrary.get(actor.model);
        if (loaded == null) {
            return;
        }
        try {
            render(loaded, actor, entity, session.playback().cursor(), event.getPartialTick(), event.getPoseStack(),
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (Throwable t) {
            // A model that breaks while drawing is dropped rather than crashing every frame.
            JustReplay.LOGGER.warn("Blockbench model {} failed to render: {}", actor.model, t.toString());
            ModelLibrary.reload(actor.model);
            ACTORS.remove(entity.getId());
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (ReplaySession.current() == null && (!ACTORS.isEmpty() || !ModelLibrary.isEmpty())) {
            ACTORS.clear();
            ModelLibrary.releaseAll();
            ActorAnimator.clearAllRenderState();
        }
    }

    /** The animation shown at the play head: the latest ANIMATION action, else the actor's looping default. */
    private record Playing(BbModel.Animation animation, double seconds) {
    }

    private static Playing playing(BbModel model, ReplayProject.ActorData actor, int cursor, float partialTick) {
        double time = cursor - 1 + partialTick;
        ReplayProject.ActorAction action = ActorAnimator.activeState(actor.actions, cursor, ActionType.ANIMATION);
        String name = actor.modelAnimation;
        String loop = "loop";
        double start = 0;
        if (action != null) {
            String[] parts = action.param.split("[|;]", 2);
            name = parts[0].trim();
            loop = parts.length > 1 ? parts[1].trim().toLowerCase(java.util.Locale.ROOT) : "";
            start = action.tick;
        }
        BbModel.Animation animation = model.animation(name);
        if (animation == null) {
            return null;
        }
        double local = animation.localTime(Math.max(0, time - start) / 20.0, loop);
        return local < 0 ? null : new Playing(animation, local);
    }

    private static void render(ModelLibrary.Loaded loaded, ReplayProject.ActorData actor, LivingEntity entity, int cursor, float partialTick,
                               PoseStack poseStack, MultiBufferSource buffers, int light) {
        BbModel model = loaded.model();
        Playing playing = playing(model, actor, cursor, partialTick);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYaw = Mth.wrapDegrees(Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) - bodyYaw);
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        if (entity.deathTime > 0) {
            float f = Mth.sqrt(Math.min(1.0F, (entity.deathTime + partialTick - 1.0F) / 20.0F * 1.6F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(f * 90.0F));
        }
        float scale = actor.modelScale / 16.0F;
        poseStack.scale(scale, scale, scale);
        Context ctx = new Context(model, playing, headYaw, pitch, overlay, light);
        for (int texture = 0; texture < loaded.textures().length; texture++) {
            ResourceLocation location = loaded.textures()[texture];
            ctx.texture = texture;
            ctx.consumer = null;
            ctx.buffers = buffers;
            ctx.location = location;
            for (BbModel.Cube cube : model.looseCubes) {
                cube(ctx, cube, poseStack);
            }
            for (BbModel.Bone bone : model.roots) {
                bone(ctx, bone, poseStack, 0);
            }
        }
        poseStack.popPose();
    }

    private static final class Context {
        final BbModel model;
        final Playing playing;
        final float headYaw, pitch;
        final int overlay, light;
        int texture;
        ResourceLocation location;
        MultiBufferSource buffers;
        VertexConsumer consumer;

        Context(BbModel model, Playing playing, float headYaw, float pitch, int overlay, int light) {
            this.model = model;
            this.playing = playing;
            this.headYaw = headYaw;
            this.pitch = pitch;
            this.overlay = overlay;
            this.light = light;
        }

        VertexConsumer consumer() {
            if (consumer == null) {
                consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(location));
            }
            return consumer;
        }
    }

    private static void bone(Context ctx, BbModel.Bone bone, PoseStack poseStack, int depth) {
        if (depth > 64) {
            return;
        }
        float rx = bone.rotation[0], ry = bone.rotation[1], rz = bone.rotation[2];
        float px = 0, py = 0, pz = 0;
        float sx = 1, sy = 1, sz = 1;
        if (ctx.playing != null) {
            BbModel.BoneAnimation animation = ctx.playing.animation().bones.get(bone.uuid);
            if (animation != null) {
                double t = ctx.playing.seconds();
                double[] r = animation.rotation.sample(t);
                if (r != null) {
                    // Blockbench shows animated x and y rotations and x positions mirrored.
                    rx -= (float) r[0];
                    ry -= (float) r[1];
                    rz += (float) r[2];
                }
                double[] p = animation.position.sample(t);
                if (p != null) {
                    px = (float) -p[0];
                    py = (float) p[1];
                    pz = (float) p[2];
                }
                double[] s = animation.scale.sample(t);
                if (s != null) {
                    sx = safeScale(s[0]);
                    sy = safeScale(s[1]);
                    sz = safeScale(s[2]);
                }
            }
        }
        if (bone.isHead) {
            ry -= ctx.headYaw;
            rx -= ctx.pitch;
        }
        poseStack.pushPose();
        poseStack.translate(bone.origin[0] + px, bone.origin[1] + py, bone.origin[2] + pz);
        rotate(poseStack, rx, ry, rz);
        if (sx != 1 || sy != 1 || sz != 1) {
            poseStack.scale(sx, sy, sz);
        }
        poseStack.translate(-bone.origin[0], -bone.origin[1], -bone.origin[2]);
        for (BbModel.Cube cube : bone.cubes) {
            cube(ctx, cube, poseStack);
        }
        for (BbModel.Bone child : bone.children) {
            bone(ctx, child, poseStack, depth + 1);
        }
        poseStack.popPose();
    }

    private static float safeScale(double value) {
        float v = (float) value;
        return Math.abs(v) < 1.0E-4F ? (v < 0 ? -1.0E-4F : 1.0E-4F) : v;
    }

    /** Blockbench Euler order: X first, then Y, then Z. */
    private static void rotate(PoseStack poseStack, float rx, float ry, float rz) {
        if (rz != 0) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(rz));
        }
        if (ry != 0) {
            poseStack.mulPose(Axis.YP.rotationDegrees(ry));
        }
        if (rx != 0) {
            poseStack.mulPose(Axis.XP.rotationDegrees(rx));
        }
    }

    private static void cube(Context ctx, BbModel.Cube cube, PoseStack poseStack) {
        boolean any = false;
        for (BbModel.Face face : cube.faces) {
            if (face != null && face.texture == ctx.texture) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        boolean rotated = cube.rotation[0] != 0 || cube.rotation[1] != 0 || cube.rotation[2] != 0;
        if (rotated) {
            poseStack.pushPose();
            poseStack.translate(cube.origin[0], cube.origin[1], cube.origin[2]);
            rotate(poseStack, cube.rotation[0], cube.rotation[1], cube.rotation[2]);
            poseStack.translate(-cube.origin[0], -cube.origin[1], -cube.origin[2]);
        }
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer consumer = ctx.consumer();
        BbModel.Texture texture = ctx.texture < ctx.model.textures.size() ? ctx.model.textures.get(ctx.texture) : null;
        float uvW = texture == null || texture.uvWidth <= 0 ? 16 : texture.uvWidth;
        float uvH = texture == null || texture.uvHeight <= 0 ? 16 : texture.uvHeight;
        float[][] bounds = {cube.from, cube.to};
        for (int f = 0; f < 6; f++) {
            BbModel.Face face = cube.faces[f];
            if (face == null || face.texture != ctx.texture) {
                continue;
            }
            float[][] uv = {
                    {face.u1 / uvW, face.v1 / uvH}, {face.u2 / uvW, face.v1 / uvH},
                    {face.u2 / uvW, face.v2 / uvH}, {face.u1 / uvW, face.v2 / uvH}};
            float[] normal = NORMALS[f];
            for (int corner : ORDER) {
                int[] c = CORNERS[f][corner];
                float x = bounds[c[0]][0];
                float y = bounds[c[1]][1];
                float z = bounds[c[2]][2];
                float[] coords = uv[Math.floorMod(corner - face.rotation, 4)];
                consumer.addVertex(pose, x, y, z).setColor(-1).setUv(coords[0], coords[1]).setOverlay(ctx.overlay)
                        .setLight(ctx.light).setNormal(pose, normal[0], normal[1], normal[2]);
            }
        }
        if (rotated) {
            poseStack.popPose();
        }
    }
}
