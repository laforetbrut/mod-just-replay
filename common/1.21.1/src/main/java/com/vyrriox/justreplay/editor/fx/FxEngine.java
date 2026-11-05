package com.vyrriox.justreplay.editor.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.util.List;

/**
 * Renders the video effects of a replay on the GPU, once per frame, over the main render target after the world
 * (and after the final pass of a shader pack) and before the HUD and the screens, so the editor preview and the
 * video export both get them:
 * <ol>
 *   <li>the filter stack: an optional two pass blur, then one grading pass (sharpen, chromatic aberration, the
 *   colour filters in stack order, letterbox);</li>
 *   <li>the picture transition of the live cut: the frozen last frame of the outgoing shot gives way to the
 *   incoming shot;</li>
 *   <li>the frame is kept as the frozen frame when a cut with a transition is coming;</li>
 *   <li>the fade in and fade out of the export range.</li>
 * </ol>
 * Nothing runs (not even a copy) when the stack is empty and no transition or fade is live. Shaders are compiled
 * once with the resources; no allocation happens per frame.
 *
 * @author vyrriox
 */
public final class FxEngine {
    private static final int MAX_OPS = 16;
    /** A cut with a transition this close (ticks) gets the current frame kept as its frozen frame. */
    private static final double CAPTURE_LEAD = 20.0;

    private static ShaderInstance grade;
    private static ShaderInstance blur;
    private static ShaderInstance transition;

    private static TextureTarget scratchA;
    private static TextureTarget scratchB;
    /** Two frozen frames, so a transition can play while the frame for the next one is kept. */
    private static final TextureTarget[] FROZEN = new TextureTarget[2];
    private static final ReplayProject.Cut[] FROZEN_CUT = new ReplayProject.Cut[2];

    private static final float[] OP_CODE = new float[MAX_OPS];
    private static final float[] OP_A = new float[MAX_OPS];
    private static final float[] OP_B = new float[MAX_OPS];
    private static int opCount;
    private static float sharpen;
    private static float blurRadius;
    private static float chroma;
    private static float letterRatio;
    private static float letterAmount;
    private static LutLibrary.Lut lut;
    private static boolean warnedOps;

    /** Cut whose outgoing frame is missing: the next frame is rendered from the outgoing camera to capture it. */
    private static ReplayProject.Cut pendingCapture;
    /** The frame being rendered shows the outgoing camera of this cut, only to capture it. */
    private static ReplayProject.Cut captureFrame;
    private static int hookCalls;
    private static int missedFrames;
    private static boolean warnedHook;

    private FxEngine() {
    }

    // ---------------------------------------------------------------- shaders

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        public static void onShaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), JustReplay.id("fx_grade"), DefaultVertexFormat.POSITION),
                    s -> grade = s);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), JustReplay.id("fx_blur"), DefaultVertexFormat.POSITION),
                    s -> blur = s);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), JustReplay.id("fx_transition"), DefaultVertexFormat.POSITION),
                    s -> transition = s);
        }
    }

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onFrameEnd(RenderFrameEvent.Post event) {
            checkHook();
        }
    }

    /** True once the effect shaders are compiled. */
    public static boolean ready() {
        return grade != null && blur != null && transition != null;
    }

    // ---------------------------------------------------------------- frame

    /** The project of the running replay when its effects may render, else null. */
    private static ReplaySession activeSession() {
        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning() || !ready() || !session.project().effectsEnabled) {
            return null;
        }
        return session;
    }

    /**
     * Camera state override of the frame about to be rendered: the outgoing camera of a transition whose frozen
     * frame is missing (after a seek into the transition), else null. Called by the camera controller.
     */
    public static double[] outgoingState(ReplayProject project, double time, float partialTick) {
        ReplayProject.Cut cut = pendingCapture;
        pendingCapture = null;
        captureFrame = null;
        if (cut == null || activeSession() == null) {
            return null;
        }
        int index = project.cutIndexAt(time);
        if (index <= 0 || project.cuts.get(index) != cut || !cut.transitionAt(time)) {
            return null;
        }
        ReplayProject.Camera outgoing = project.camera(project.cuts.get(index - 1).camera);
        double[] state = CameraRig.sample(outgoing, Math.max(0.0, cut.tick - 0.01), partialTick, false);
        if (state != null) {
            captureFrame = cut;
        }
        return state;
    }

    /** Called by the game renderer after the world is drawn and before the HUD. */
    public static void render(float partialTick) {
        hookCalls++;
        ReplayProject.Cut capturing = captureFrame;
        captureFrame = null;
        ReplaySession session = activeSession();
        if (session == null) {
            return;
        }
        ReplayProject project = session.project();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        boolean preview = session.isCameraPathActive();
        double time = CameraController.frameTime(session, partialTick);
        List<ReplayProject.Cut> cuts = project.cuts;
        int index = preview && !cuts.isEmpty() ? project.cutIndexAt(time) : -1;

        // Filters of the camera on screen; the capture frame shows the outgoing camera.
        String camera = null;
        if (preview) {
            if (capturing != null && index > 0) {
                camera = cuts.get(index - 1).camera;
            } else {
                camera = index >= 0 ? cuts.get(index).camera : project.selectedCamera;
            }
        }
        collect(project, camera, time, main.height / 1080.0F);
        boolean grading = opCount > 0 || sharpen > 0.0F || blurRadius > 0.0F || chroma > 0.0F || letterAmount > 0.0F && letterRatio > 0.0F;

        ReplayProject.Cut live = index > 0 ? cuts.get(index) : null;
        boolean inTransition = live != null && live.transitionAt(time);
        ReplayProject.Cut next = index >= 0 && index + 1 < cuts.size() ? cuts.get(index + 1) : null;
        boolean upcoming = next != null && next.transitionType() != TransitionType.CUT && time < next.tick && next.tick - time <= CAPTURE_LEAD;
        float fade = fadeAmount(session, project, time, preview);
        if (!grading && !inTransition && !upcoming && fade <= 0.0F && capturing == null) {
            return;
        }
        prepare(main);
        try {
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            if (grading) {
                gradePass(main, time);
            }
            if (capturing != null) {
                // Frame rendered from the outgoing camera: keep it, the transition shows from the next frame.
                capture(main, capturing, null);
            } else if (inTransition) {
                int slot = slotOf(live);
                if (slot >= 0) {
                    float progress = (float) ((time - live.tick) / Math.max(1, live.transitionTicks));
                    TransitionType type = live.transitionType();
                    transitionPass(main, FROZEN[slot], type.code, progress, TransitionType.dirX(live.transitionDir),
                            TransitionType.dirY(live.transitionDir), 0.0F, project.fadeWhite);
                } else {
                    pendingCapture = live;
                }
            }
            if (upcoming && capturing == null) {
                capture(main, next, inTransition ? live : null);
            }
            if (fade > 0.0F) {
                transitionPass(main, scratchA, 0, 0.0F, 0.0F, 0.0F, fade, project.fadeWhite);
            }
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            main.bindWrite(true);
        }
    }

    /** Warns once when effects should have rendered but the game renderer hook never ran (a mod conflict). */
    public static void checkHook() {
        boolean ran = hookCalls > 0;
        hookCalls = 0;
        if (warnedHook || activeSession() == null || Minecraft.getInstance().level == null) {
            return;
        }
        missedFrames = ran ? 0 : missedFrames + 1;
        if (missedFrames > 120) {
            warnedHook = true;
            JustReplay.LOGGER.warn("The effect hook in the game renderer does not run; video effects are off (another mod changes GameRenderer.render)");
        }
    }

    /** Fade of the export range at a time: 0 for none, 1 for a full black (or white) frame. */
    private static float fadeAmount(ReplaySession session, ReplayProject project, double time, boolean preview) {
        if (!preview || project.fadeIn <= 0 && project.fadeOut <= 0) {
            return 0.0F;
        }
        int start, end;
        VideoExporter exporter = VideoExporter.current();
        if (exporter != null) {
            start = exporter.startTick();
            end = exporter.endTick();
        } else if (!project.cuts.isEmpty()) {
            start = project.cuts.get(0).tick;
            end = project.cutTrackEnd(session.playback().duration());
        } else {
            ReplayProject.Camera camera = project.selected();
            if (camera == null || !camera.isPath() || camera.keyframes.size() < 2) {
                return 0.0F;
            }
            start = camera.keyframes.get(0).tick;
            end = camera.keyframes.get(camera.keyframes.size() - 1).tick;
        }
        if (time < start || time > end) {
            return 0.0F;
        }
        float amount = 0.0F;
        if (project.fadeIn > 0 && time < start + project.fadeIn) {
            amount = (float) (1.0 - (time - start) / project.fadeIn);
        }
        if (project.fadeOut > 0 && time > end - project.fadeOut) {
            amount = Math.max(amount, (float) ((time - (end - project.fadeOut)) / project.fadeOut));
        }
        return Math.max(0.0F, Math.min(1.0F, amount));
    }

    // ---------------------------------------------------------------- stack

    /** Packs the filters live at a time into the uniform arrays. */
    private static void collect(ReplayProject project, String camera, double time, float pixelScale) {
        opCount = 0;
        sharpen = 0.0F;
        blurRadius = 0.0F;
        chroma = 0.0F;
        letterRatio = 0.0F;
        letterAmount = 0.0F;
        lut = null;
        List<FxEffect> effects = project.effects;
        for (int i = 0; i < effects.size(); i++) {
            FxEffect effect = effects.get(i);
            if (!effect.enabled || !effect.isGlobal() && !effect.camera.equals(camera)) {
                continue;
            }
            FxType type = effect.fxType();
            if (type == null) {
                continue;
            }
            FxParam[] params = type.params;
            float a = effect.value(params[0], time);
            float b = params.length > 1 ? effect.value(params[1], time) : 0.0F;
            switch (type) {
                case SHARPEN -> sharpen += a;
                case BLUR -> blurRadius = Math.max(blurRadius, a * pixelScale);
                case CHROMATIC -> chroma += a * pixelScale;
                case LETTERBOX -> {
                    int choice = Math.max(0, Math.min(FxType.LETTERBOX_RATIOS.length - 1, Math.round(a)));
                    letterRatio = FxType.LETTERBOX_RATIOS[choice];
                    letterAmount = b;
                }
                default -> {
                    if (type == FxType.LUT) {
                        if (lut != null) {
                            continue; // one table per frame
                        }
                        lut = LutLibrary.get(effect.file);
                        if (lut == null) {
                            continue;
                        }
                    }
                    if (type == FxType.GRAIN) {
                        b *= pixelScale;
                    }
                    if (opCount >= MAX_OPS) {
                        if (!warnedOps) {
                            warnedOps = true;
                            JustReplay.LOGGER.warn("More than {} colour filters are live, the extra ones are skipped", MAX_OPS);
                        }
                        continue;
                    }
                    OP_CODE[opCount] = type.op;
                    OP_A[opCount] = a;
                    OP_B[opCount] = b;
                    opCount++;
                }
            }
        }
    }

    // ---------------------------------------------------------------- passes

    private static void gradePass(RenderTarget main, double time) {
        copy(main, scratchA);
        if (blurRadius > 0.25F) {
            blur.setSampler("DiffuseSampler", scratchA);
            set(blur, "OutSize", main.width, main.height);
            set(blur, "BlurDir", 1.0F, 0.0F);
            set(blur, "Radius", blurRadius);
            draw(blur, scratchB);
            blur.setSampler("DiffuseSampler", scratchB);
            set(blur, "BlurDir", 0.0F, 1.0F);
            draw(blur, scratchA);
        }
        grade.setSampler("DiffuseSampler", scratchA);
        grade.setSampler("LutSampler", lut != null ? lut.texture() : scratchB);
        set(grade, "OutSize", main.width, main.height);
        Uniform count = grade.getUniform("OpCount");
        if (count != null) {
            count.set(opCount);
        }
        setArray(grade, "OpCode", OP_CODE);
        setArray(grade, "OpA", OP_A);
        setArray(grade, "OpB", OP_B);
        set(grade, "Sharpen", sharpen);
        set(grade, "Chroma", chroma);
        set(grade, "Letterbox", letterRatio, letterAmount);
        // Grain changes with the frame time, so an export frame always gets the same grain.
        set(grade, "Seed", (float) ((time * 7.31) % 997.0));
        Uniform info = grade.getUniform("LutInfo");
        if (info != null) {
            if (lut != null) {
                info.set((float) lut.size(), (float) lut.width(), (float) lut.height());
            } else {
                info.set(0.0F, 1.0F, 1.0F);
            }
        }
        RenderSystem.colorMask(true, true, true, false);
        draw(grade, main);
        RenderSystem.colorMask(true, true, true, true);
    }

    private static void transitionPass(RenderTarget main, RenderTarget previous, int type, float progress, float dirX, float dirY,
                                       float fade, boolean white) {
        copy(main, scratchA);
        transition.setSampler("DiffuseSampler", scratchA);
        transition.setSampler("PrevSampler", previous);
        set(transition, "OutSize", main.width, main.height);
        Uniform t = transition.getUniform("Type");
        if (t != null) {
            t.set(type);
        }
        set(transition, "Progress", progress);
        set(transition, "Direction", dirX, dirY);
        float c = white ? 1.0F : 0.0F;
        Uniform color = transition.getUniform("FadeColor");
        if (color != null) {
            color.set(c, c, c);
        }
        set(transition, "FadeAmount", fade);
        RenderSystem.colorMask(true, true, true, false);
        draw(transition, main);
        RenderSystem.colorMask(true, true, true, true);
    }

    /** Keeps the frame as the frozen frame of a cut, never in the slot a playing transition reads. */
    private static void capture(RenderTarget main, ReplayProject.Cut cut, ReplayProject.Cut playing) {
        int slot = slotOf(cut);
        if (slot < 0) {
            int busy = slotOf(playing);
            slot = busy == 0 ? 1 : 0;
        }
        FROZEN_CUT[slot] = cut;
        copy(main, FROZEN[slot]);
    }

    private static int slotOf(ReplayProject.Cut cut) {
        if (cut == null) {
            return -1;
        }
        return FROZEN_CUT[0] == cut ? 0 : FROZEN_CUT[1] == cut ? 1 : -1;
    }

    /** Creates or resizes the work targets to the render size; a resize forgets the frozen frames. */
    private static void prepare(RenderTarget main) {
        scratchA = ensure(scratchA, main);
        scratchB = ensure(scratchB, main);
        for (int i = 0; i < FROZEN.length; i++) {
            TextureTarget before = FROZEN[i];
            int w = before == null ? -1 : before.width, h = before == null ? -1 : before.height;
            FROZEN[i] = ensure(before, main);
            if (FROZEN[i] != before || w != main.width || h != main.height) {
                FROZEN_CUT[i] = null;
            }
        }
    }

    private static TextureTarget ensure(TextureTarget target, RenderTarget main) {
        if (target == null) {
            target = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }
        return target;
    }

    private static void copy(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Draws a full screen quad with a shader into a target. */
    private static void draw(ShaderInstance shader, RenderTarget out) {
        out.bindWrite(true);
        shader.apply();
        BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(0.0F, 0.0F, 0.0F);
        builder.addVertex(1.0F, 0.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(builder.buildOrThrow());
        shader.clear();
    }

    private static void set(ShaderInstance shader, String name, float value) {
        Uniform u = shader.getUniform(name);
        if (u != null) {
            u.set(value);
        }
    }

    private static void set(ShaderInstance shader, String name, float x, float y) {
        Uniform u = shader.getUniform(name);
        if (u != null) {
            u.set(x, y);
        }
    }

    private static void setArray(ShaderInstance shader, String name, float[] values) {
        Uniform u = shader.getUniform(name);
        if (u != null) {
            u.set(values);
        }
    }
}
