package com.vyrriox.justreplay.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Renders the camera path to a video. The game clock is driven frame by frame (see the delta tracker mixin),
 * so every frame is exact whatever the machine speed, and frames are piped to an external FFmpeg process.
 * <p>
 * An export runs in three phases: a pre-roll that plays up to one second of real ticks before the first frame
 * (entities, particles and animations are in motion when capture starts), a settle phase that holds the clock
 * on the first frame until the chunks around the camera are built, then the capture itself, where frame n
 * shows exactly {@code startTick + n * 20 / fps}.
 *
 * @author vyrriox
 */
public final class VideoExporter {
    private static final byte[] END = new byte[0];
    /** Ticks played before the first frame. */
    private static final int PRE_ROLL_TICKS = 20;
    /** Pre-roll frames after which the pre-roll gives up waiting for the replay cursor. */
    private static final int MAX_PRE_ROLL_FRAMES = 600;
    /** Consecutive frames with every visible section compiled before capture starts. */
    private static final int READY_FRAMES = 5;
    /** Settle frames after which capture starts even if chunks are still building. */
    private static final int MAX_SETTLE_FRAMES = 1200;
    private static VideoExporter current;

    private enum Phase {
        PRE_ROLL, SETTLE, CAPTURE
    }

    /** What the game clock does on one rendered frame: client ticks to run and the partial tick to show. */
    public record TimerStep(int ticks, float deltaTicks, float partialTick) {
        private static final TimerStep HOLD = new TimerStep(0, 0.0F, 0.0F);
        private static final TimerStep ONE_TICK = new TimerStep(1, 1.0F, 0.0F);
    }

    private final ReplaySession session;
    private final int startTick;
    private final int endTick;
    private final int fps;
    private final Path output;
    private final Process process;
    private final OutputStream stdin;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6);
    private final Thread writerThread;
    private final int width;
    private final int height;
    private final boolean previousHideGui;
    private final boolean previousPauseOnLostFocus;
    private final int warmupFrames;
    private final int captureCursor;
    private Phase phase = Phase.PRE_ROLL;
    private int warmedFrames;
    private int preRollFrames;
    private int settleFrames;
    private int readyStreak;
    private long ticksIssued;
    private boolean timerPrepared;
    private int frames;
    private final ExportAudio audio;
    private volatile boolean failed;
    private boolean finished;

    private VideoExporter(ReplaySession session, int startTick, int endTick, int fps, Path output, Process process, int width, int height,
                          boolean previousHideGui, boolean previousPauseOnLostFocus) {
        this.session = session;
        this.startTick = startTick;
        this.endTick = endTick;
        this.fps = fps;
        this.output = output;
        this.process = process;
        this.stdin = process.getOutputStream();
        this.width = width;
        this.height = height;
        this.warmupFrames = JRConfig.EXPORT_WARMUP_FRAMES.get();
        this.captureCursor = startTick + 1;
        this.previousHideGui = previousHideGui;
        this.previousPauseOnLostFocus = previousPauseOnLostFocus;
        this.audio = new ExportAudio(fps);
        this.writerThread = new Thread(this::pump, "JustReplay export");
        this.writerThread.setDaemon(true);
    }

    public static boolean isActive() {
        return current != null;
    }

    public static VideoExporter current() {
        return current;
    }

    /** True while the game clock must follow the export instead of the wall clock. */
    public static boolean isFixedStep() {
        return current != null;
    }

    /** Clock step of the frame about to be rendered, or null when no export is running. */
    public static TimerStep nextTimerStep() {
        VideoExporter e = current;
        return e == null ? null : e.timerStep();
    }

    public float progress() {
        long total = (long) (endTick - startTick) * fps / 20 + 1;
        return Math.min(1.0F, (float) frames / Math.max(1L, total));
    }

    /** Starts an export of the camera path between two ticks. Returns an error message, or null on success. */
    public static Component start(ReplaySession session, int startTick, int endTick, String fileName) {
        if (current != null) {
            return Component.translatable("justreplay.export.error.running");
        }
        if (endTick <= startTick) {
            return Component.translatable("justreplay.export.error.range");
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        int windowWidth = target.width;
        int windowHeight = target.height;
        int fps = JRConfig.EXPORT_FPS.get();
        ExportResolution resolution = JRConfig.EXPORT_RESOLUTION.get();
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank()) {
            safeName = "export";
        }
        if (!safeName.endsWith(".mp4")) {
            safeName += ".mp4";
        }
        Path output = ReplayStorage.exportRoot().resolve(safeName);
        String base = safeName.substring(0, safeName.length() - 4);
        for (int n = 2; java.nio.file.Files.exists(output); n++) {
            // Never overwrite an earlier export.
            output = ReplayStorage.exportRoot().resolve(base + "_" + n + ".mp4");
        }

        // Render at the chosen size when the GPU allows it, otherwise at window size with FFmpeg scaling.
        int outWidth = resolution.isWindow() ? windowWidth : resolution.width();
        int outHeight = resolution.isWindow() ? windowHeight : resolution.height();
        boolean trueSize = resolution.isWindow() || ExportRenderSize.fits(outWidth, outHeight);
        int renderWidth = trueSize ? outWidth : windowWidth;
        int renderHeight = trueSize ? outHeight : windowHeight;
        Process process;
        try {
            process = launch(output, fps, renderWidth, renderHeight, trueSize ? 0 : outWidth, outHeight);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot start ffmpeg", e);
            return Component.translatable("justreplay.export.error.ffmpeg", JRConfig.FFMPEG_PATH.get());
        }

        boolean previousHideGui = mc.options.hideGui;
        boolean previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.getSoundManager().stop(); // live sounds would be heard out of sync; the track is rebuilt offline
        mc.options.hideGui = true;
        mc.options.pauseOnLostFocus = false;
        mc.setScreen(null);
        // The render size is changed before the warm-up so shader packs rebuild their pipeline before capture.
        ExportRenderSize.lock();
        if (!ExportRenderSize.apply(renderWidth, renderHeight)) {
            JustReplay.LOGGER.warn("Cannot render at {}x{}, rendering at window size and scaling with FFmpeg instead", renderWidth, renderHeight);
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            RenderTarget fallback = mc.getMainRenderTarget();
            renderWidth = fallback.width;
            renderHeight = fallback.height;
            try {
                process = launch(output, fps, renderWidth, renderHeight, outWidth, outHeight);
            } catch (IOException e) {
                JustReplay.LOGGER.error("Cannot start ffmpeg", e);
                ExportRenderSize.unlock();
                mc.options.hideGui = previousHideGui;
                mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
                SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("justreplay.export.failed"), Component.literal(output.getFileName().toString()));
                return Component.translatable("justreplay.export.error.ffmpeg", JRConfig.FFMPEG_PATH.get());
            }
        }

        VideoExporter exporter = new VideoExporter(session, startTick, endTick, fps, output, process, renderWidth, renderHeight,
                previousHideGui, previousPauseOnLostFocus);
        current = exporter;
        exporter.writerThread.start();
        Playback playback = session.playback();
        playback.resetSpeed();
        playback.setPaused(false);
        playback.seek(Math.max(0, startTick + 1 - PRE_ROLL_TICKS));
        JustReplay.LOGGER.info("Exporting ticks {}..{} at {} fps, rendered at {}x{}, output {}x{}{} to {}", startTick, endTick, fps,
                renderWidth, renderHeight, outWidth, outHeight, renderWidth != outWidth || renderHeight != outHeight ? " (scaled by FFmpeg)" : "",
                output);
        return null;
    }

    /**
     * Starts FFmpeg reading raw RGBA frames of the render size. A non-zero {@code scaleWidth} scales the frames
     * to {@code scaleWidth x scaleHeight} (Lanczos, aspect kept by cropping); otherwise only an odd edge is
     * cropped. The output is H.264 tagged BT.709, limited range.
     */
    private static Process launch(Path output, int fps, int renderWidth, int renderHeight, int scaleWidth, int scaleHeight) throws IOException {
        String size = scaleWidth > 0
                ? "scale=" + scaleWidth + ":" + scaleHeight + ":force_original_aspect_ratio=increase:flags=lanczos,crop=" + scaleWidth + ":" + scaleHeight
                : "crop=trunc(iw/2)*2:trunc(ih/2)*2";
        // The YUV conversion happens in this scale filter, so it uses the BT.709 matrix the stream is tagged with.
        String filter = size + ",scale=iw:ih:flags=lanczos:out_color_matrix=bt709:out_range=tv,format=yuv420p";
        List<String> command = new ArrayList<>(List.of(
                JRConfig.FFMPEG_PATH.get(), "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", renderWidth + "x" + renderHeight, "-r", Integer.toString(fps), "-i", "-",
                "-vf", filter,
                "-c:v", "libx264", "-preset", "medium", "-crf", Integer.toString(JRConfig.EXPORT_CRF.get()),
                "-pix_fmt", "yuv420p", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                "-movflags", "+faststart", output.toString()));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.resolveSibling(output.getFileName() + ".log").toFile())
                .start();
    }

    private TimerStep timerStep() {
        timerPrepared = false;
        Playback playback = session.playback();
        switch (phase) {
            case PRE_ROLL -> {
                // One real tick per frame until the cursor is on the first frame, never past it.
                if (playback.cursor() < captureCursor && playback.cursor() < playback.duration() && !playback.isPaused()
                        && preRollFrames < MAX_PRE_ROLL_FRAMES) {
                    return TimerStep.ONE_TICK;
                }
                phase = Phase.SETTLE;
                return TimerStep.HOLD;
            }
            case SETTLE -> {
                return TimerStep.HOLD;
            }
            default -> {
                // Frame n shows startTick + n * 20 / fps; computed from the frame number so it never drifts, and
                // idempotent so a frame that could not be captured is simply rendered again.
                long video = (long) frames * 20;
                long target = video / fps;
                int ticks = (int) Math.max(0, target - ticksIssued);
                ticksIssued = Math.max(ticksIssued, target);
                timerPrepared = true;
                return new TimerStep(ticks, 20.0F / fps, (float) (video % fps) / fps);
            }
        }
    }

    /** Called after every rendered frame. */
    public static void onFrameRendered(float partialTick) {
        VideoExporter e = current;
        if (e != null) {
            e.frame(partialTick);
        }
    }

    private void frame(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (failed || ReplaySession.current() != session) {
            finish(false);
            return;
        }
        if (phase != Phase.CAPTURE) {
            warmUp(mc);
            return;
        }
        if (!timerPrepared || mc.screen != null || mc.getOverlay() != null) {
            // Never burn a screen into the video; this frame is rendered again once it is gone.
            return;
        }
        timerPrepared = false;
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width != width || target.height != height) {
            JustReplay.LOGGER.warn("Render size changed during export ({}x{} instead of {}x{}), stopping", target.width, target.height, width, height);
            finish(false);
            return;
        }
        try (NativeImage image = Screenshot.takeScreenshot(target)) {
            int[] pixels = image.getPixelsRGBA();
            ByteBuffer bytes = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asIntBuffer().put(pixels);
            byte[] frame = bytes.array();
            // Never block forever: if ffmpeg died the writer stops draining the queue.
            while (!queue.offer(frame, 100, TimeUnit.MILLISECONDS)) {
                if (failed || !process.isAlive()) {
                    finish(false);
                    return;
                }
            }
        } catch (InterruptedException ex) {
            finish(false);
            return;
        }
        frames++;
        Camera camera = mc.gameRenderer.getMainCamera();
        audio.onFrame(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z, camera.getYRot());
        Playback playback = session.playback();
        boolean reachedEnd = (long) (frames - 1) * 20 >= (long) (endTick - startTick) * fps;
        if (reachedEnd || playback.isPaused() && playback.cursor() >= playback.duration()) {
            finish(true);
        }
    }

    /** Pre-roll and settle frames: nothing is captured, chunks build and the world gets in motion. */
    private void warmUp(Minecraft mc) {
        warmedFrames++;
        if (phase == Phase.PRE_ROLL) {
            preRollFrames++;
            return;
        }
        settleFrames++;
        readyStreak = mc.levelRenderer.hasRenderedAllSections() ? readyStreak + 1 : 0;
        boolean built = readyStreak >= READY_FRAMES;
        if (warmedFrames < warmupFrames || !built && settleFrames < MAX_SETTLE_FRAMES) {
            return;
        }
        if (!built) {
            JustReplay.LOGGER.warn("Chunks still building after {} frames, starting capture anyway", warmedFrames);
        }
        if (session.playback().cursor() != captureCursor) {
            JustReplay.LOGGER.warn("Pre-roll ended at tick {} instead of {}", session.playback().cursor(), captureCursor);
        }
        phase = Phase.CAPTURE;
        JustReplay.LOGGER.debug("Export warm-up done after {} frames", warmedFrames);
    }

    private void pump() {
        try {
            while (true) {
                byte[] frame = queue.take();
                if (frame == END) {
                    break;
                }
                stdin.write(frame);
            }
        } catch (IOException | InterruptedException e) {
            failed = true;
            JustReplay.LOGGER.error("FFmpeg stopped accepting frames", e);
            queue.clear();
            process.destroyForcibly();
        } finally {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void finish(boolean success) {
        if (finished) {
            return;
        }
        finished = true;
        current = null;
        Minecraft mc = Minecraft.getInstance();
        try {
            ExportRenderSize.unlock();
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot restore the render size after an export", t);
        }
        mc.options.hideGui = previousHideGui;
        mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
        session.playback().setPaused(true);
        if (!queue.offer(END)) {
            // Queue full and the writer is stuck: give up on this export instead of freezing the game.
            failed = true;
            queue.clear();
            queue.offer(END);
            process.destroyForcibly();
        }
        Thread waiter = new Thread(() -> {
            boolean ok = success;
            try {
                writerThread.join();
                if (!process.waitFor(10, TimeUnit.MINUTES)) {
                    process.destroy();
                    ok = false;
                }
                ok = ok && process.exitValue() == 0;
                if (ok && !audio.isEmpty()) {
                    ok = addAudio();
                }
            } catch (InterruptedException e) {
                ok = false;
            }
            boolean result = ok;
            mc.execute(() -> SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(result ? "justreplay.export.done" : "justreplay.export.failed"),
                    Component.literal(output.getFileName().toString())));
            JustReplay.LOGGER.info("Export {} ({} frames): {}", result ? "finished" : "failed", frames, output);
        }, "JustReplay export finisher");
        waiter.setDaemon(true);
        waiter.start();
    }

    /** Mixes the logged sounds and muxes them into the finished video (video stream copied, AAC audio). */
    private boolean addAudio() {
        Path wav = output.resolveSibling(output.getFileName() + ".wav");
        Path muxed = output.resolveSibling(output.getFileName() + ".tmp.mp4");
        try {
            audio.writeWav(wav, frames);
            Process mux = new ProcessBuilder(JRConfig.FFMPEG_PATH.get(), "-y", "-i", output.toString(), "-i", wav.toString(),
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", muxed.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.resolveSibling(output.getFileName() + ".audio.log").toFile())
                    .start();
            if (!mux.waitFor(10, TimeUnit.MINUTES) || mux.exitValue() != 0) {
                JustReplay.LOGGER.warn("Could not add the audio track, the video is kept without sound");
                return true;
            }
            java.nio.file.Files.move(muxed, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Could not add the audio track, the video is kept without sound", e);
            return true;
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(wav);
                java.nio.file.Files.deleteIfExists(muxed);
            } catch (IOException ignored) {
            }
        }
    }

    /** Sounds never play live during an export; they are logged for the audio track instead. */
    public static void onSound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        VideoExporter e = current;
        if (e == null || event.getSound() == null) {
            return;
        }
        e.audio.onSound(event.getSound(), e.frames, e.phase == Phase.CAPTURE);
        event.setSound(null);
    }

    public static void cancel() {
        VideoExporter e = current;
        if (e != null) {
            e.failed = true;
            e.finish(false);
        }
    }
}
