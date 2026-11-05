package com.vyrriox.justreplay.editor.fx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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
 * Nothing runs (not even a copy) when the stack is empty and no transition or fade is live. The pipelines are not
 * registered with a loader: the device compiles them on first use and again after a resource reload, so a broken
 * shader turns the effects off instead of failing the resource load.
 *
 * @author vyrriox
 */
public final class FxEngine {
    private static final int MAX_OPS = 16;
    /** A cut with a transition this close (ticks) gets the current frame kept as its frozen frame. */
    private static final double CAPTURE_LEAD = 20.0;

    /** Passes of one frame that take uniforms: two of blur, the grade, the transition, the fade. */
    private static final int UBO_SLOTS = 5;
    private static final int GRADE_UBO_SIZE = new Std140SizeCalculator().putVec2().putInt().putFloat().putFloat().putFloat()
            .putVec2().putVec3().align(16).get() + MAX_OPS * 16;
    private static final int BLUR_UBO_SIZE = new Std140SizeCalculator().putVec2().putVec2().putFloat().get();
    private static final int TRANSITION_UBO_SIZE = new Std140SizeCalculator().putVec2().putVec2().putInt().putFloat().putFloat()
            .putVec3().get();

    /** Ring of uniform buffers, one per frame in flight, each with one aligned slot per pass. */
    private static MappableRingBuffer ubo;
    private static int uboSlotSize;
    private static int uboSlot;

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
    private static boolean warnedPipelines;

    private FxEngine() {
    }

    // ---------------------------------------------------------------- pipelines

    /** Loaded on first use, once the game has a render device. */
    private static final class Pipelines {
        static final RenderPipeline GRADE = screenPass("fx_grade", "FxGrade", ColorTargetState.WRITE_COLOR)
                .withSampler("DiffuseSampler").withSampler("LutSampler").build();
        static final RenderPipeline BLUR = screenPass("fx_blur", "FxBlur", ColorTargetState.WRITE_ALL)
                .withSampler("DiffuseSampler").build();
        static final RenderPipeline TRANSITION = screenPass("fx_transition", "FxTransition", ColorTargetState.WRITE_COLOR)
                .withSampler("DiffuseSampler").withSampler("PrevSampler").build();

        private static RenderPipeline.Builder screenPass(String name, String uniforms, int writeMask) {
            return RenderPipeline.builder()
                    .withLocation(JustReplay.id("pipeline/" + name))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(JustReplay.id("core/" + name))
                    .withUniform(uniforms, UniformType.UNIFORM_BUFFER)
                    .withColorTargetState(new ColorTargetState(Optional.empty(), writeMask))
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
        }
    }

    /** End of a render frame. */
    public static void onFrameEnd() {
        checkHook();
    }

    /** True when the effect pipelines compile; the device keeps the result until the next resource reload. */
    public static boolean ready() {
        GpuDevice device = RenderSystem.getDevice();
        boolean valid = device.precompilePipeline(Pipelines.GRADE).isValid()
                && device.precompilePipeline(Pipelines.BLUR).isValid()
                && device.precompilePipeline(Pipelines.TRANSITION).isValid();
        if (!valid && !warnedPipelines) {
            warnedPipelines = true;
            JustReplay.LOGGER.warn("The effect shaders did not compile; video effects are off");
        }
        return valid;
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
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        uboSlot = 0;
        try {
            if (grading) {
                gradePass(encoder, main, time);
            }
            if (capturing != null) {
                // Frame rendered from the outgoing camera: keep it, the transition shows from the next frame.
                capture(encoder, main, capturing, null);
            } else if (inTransition) {
                int slot = slotOf(live);
                if (slot >= 0) {
                    float progress = (float) ((time - live.tick) / Math.max(1, live.transitionTicks));
                    TransitionType type = live.transitionType();
                    transitionPass(encoder, main, FROZEN[slot], type.code, progress, TransitionType.dirX(live.transitionDir),
                            TransitionType.dirY(live.transitionDir), 0.0F, project.fadeWhite);
                } else {
                    pendingCapture = live;
                }
            }
            if (upcoming && capturing == null) {
                capture(encoder, main, next, inTransition ? live : null);
            }
            if (fade > 0.0F) {
                transitionPass(encoder, main, scratchA, 0, 0.0F, 0.0F, 0.0F, fade, project.fadeWhite);
            }
        } finally {
            if (uboSlot > 0) {
                ubo.rotate();
            }
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

    private static void gradePass(CommandEncoder encoder, RenderTarget main, double time) {
        copy(encoder, main, scratchA);
        GpuSampler linear = linearSampler();
        if (blurRadius > 0.25F) {
            blurPass(encoder, main, scratchA, scratchB, 1.0F, 0.0F, linear);
            blurPass(encoder, main, scratchB, scratchA, 0.0F, 1.0F, linear);
        }
        GpuBufferSlice uniforms = nextSlot();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms, false, true)) {
            Std140Builder builder = Std140Builder.intoBuffer(view.data());
            builder.putVec2(main.width, main.height);
            builder.putInt(opCount);
            builder.putFloat(sharpen);
            builder.putFloat(chroma);
            // Grain changes with the frame time, so an export frame always gets the same grain.
            builder.putFloat((float) ((time * 7.31) % 997.0));
            builder.putVec2(letterRatio, letterAmount);
            if (lut != null) {
                builder.putVec3((float) lut.size(), (float) lut.width(), (float) lut.height());
            } else {
                builder.putVec3(0.0F, 1.0F, 1.0F);
            }
            for (int i = 0; i < MAX_OPS; i++) {
                builder.putVec4(OP_CODE[i], OP_A[i], OP_B[i], 0.0F);
            }
        }
        try (RenderPass pass = openPass(encoder, Pipelines.GRADE, main, "FxGrade", uniforms)) {
            pass.bindTexture("DiffuseSampler", scratchA.getColorTextureView(), linear);
            if (lut != null) {
                pass.bindTexture("LutSampler", lut.texture().getTextureView(), lut.texture().getSampler());
            } else {
                pass.bindTexture("LutSampler", scratchB.getColorTextureView(), linear);
            }
            pass.draw(0, 3);
        }
    }

    private static void blurPass(CommandEncoder encoder, RenderTarget main, RenderTarget from, RenderTarget to, float dirX, float dirY, GpuSampler sampler) {
        GpuBufferSlice uniforms = nextSlot();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms, false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putVec2(main.width, main.height)
                    .putVec2(dirX, dirY)
                    .putFloat(blurRadius);
        }
        try (RenderPass pass = openPass(encoder, Pipelines.BLUR, to, "FxBlur", uniforms)) {
            pass.bindTexture("DiffuseSampler", from.getColorTextureView(), sampler);
            pass.draw(0, 3);
        }
    }

    private static void transitionPass(CommandEncoder encoder, RenderTarget main, RenderTarget previous, int type, float progress, float dirX, float dirY,
                                       float fade, boolean white) {
        copy(encoder, main, scratchA);
        float c = white ? 1.0F : 0.0F;
        GpuBufferSlice uniforms = nextSlot();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms, false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putVec2(main.width, main.height)
                    .putVec2(dirX, dirY)
                    .putInt(type)
                    .putFloat(progress)
                    .putFloat(fade)
                    .putVec3(c, c, c);
        }
        GpuSampler linear = linearSampler();
        try (RenderPass pass = openPass(encoder, Pipelines.TRANSITION, main, "FxTransition", uniforms)) {
            pass.bindTexture("DiffuseSampler", scratchA.getColorTextureView(), linear);
            pass.bindTexture("PrevSampler", previous.getColorTextureView(), linear);
            pass.draw(0, 3);
        }
    }

    /** Keeps the frame as the frozen frame of a cut, never in the slot a playing transition reads. */
    private static void capture(CommandEncoder encoder, RenderTarget main, ReplayProject.Cut cut, ReplayProject.Cut playing) {
        int slot = slotOf(cut);
        if (slot < 0) {
            int busy = slotOf(playing);
            slot = busy == 0 ? 1 : 0;
        }
        FROZEN_CUT[slot] = cut;
        copy(encoder, main, FROZEN[slot]);
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
        if (ubo == null) {
            int largest = Math.max(GRADE_UBO_SIZE, Math.max(BLUR_UBO_SIZE, TRANSITION_UBO_SIZE));
            uboSlotSize = Mth.roundToward(largest, RenderSystem.getDevice().getUniformOffsetAlignment());
            ubo = new MappableRingBuffer(() -> "Just Replay effects", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SLOTS * uboSlotSize);
        }
    }

    private static TextureTarget ensure(TextureTarget target, RenderTarget main) {
        if (target == null) {
            target = new TextureTarget("Just Replay effects", main.width, main.height, false);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height);
        }
        return target;
    }

    private static GpuSampler linearSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    private static void copy(CommandEncoder encoder, RenderTarget from, RenderTarget to) {
        encoder.copyTextureToTexture(from.getColorTexture(), to.getColorTexture(), 0, 0, 0, 0, 0, from.width, from.height);
    }

    /** The uniform block of the next pass of this frame, in the buffer the ring has for it. */
    private static GpuBufferSlice nextSlot() {
        return ubo.currentBuffer().slice((long) uboSlot++ * uboSlotSize, uboSlotSize);
    }

    /** Starts a full screen pass into a target; the caller binds the samplers and draws the triangle. */
    private static RenderPass openPass(CommandEncoder encoder, RenderPipeline pipeline, RenderTarget out, String block, GpuBufferSlice uniforms) {
        RenderPass pass = encoder.createRenderPass(() -> "Just Replay effect", out.getColorTextureView(), OptionalInt.empty());
        pass.setPipeline(pipeline);
        pass.setUniform(block, uniforms);
        return pass;
    }
}
