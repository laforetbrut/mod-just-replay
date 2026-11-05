package com.vyrriox.justreplay.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
    /** Frames copied to the GPU staging buffers and not read back yet. Bounds the memory held by a slow GPU. */
    private static final int MAX_PENDING_READBACKS = 4;
    private static final long READBACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
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

    /**
     * Frame rate, quality, size and output of one export. A width and height above zero override the resolution;
     * a PNG sequence writes one picture per frame into a folder, with the sound as a WAV file beside them.
     */
    public record Settings(int fps, int crf, ExportResolution resolution, int width, int height, boolean pngSequence) {
        /** The export settings of the config file, as the export screen leaves them. */
        public static Settings fromConfig() {
            return new Settings(JRConfig.EXPORT_FPS.get(), JRConfig.EXPORT_CRF.get(), JRConfig.EXPORT_RESOLUTION.get(), 0, 0, false);
        }
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
    private final boolean pngSequence;
    private final java.util.concurrent.CompletableFuture<Boolean> completion = new java.util.concurrent.CompletableFuture<>();
    /** For a still: the PNG the first frame is moved to once the one frame export is done. */
    private Path still;
    private volatile boolean failed;
    private volatile boolean cancelled;
    private boolean finished;
    /** All frames are issued: only the readbacks in flight are left before the export can finish. */
    private boolean draining;
    private int pendingReadbacks;
    private long lastReadbackNanos;
    private final ArrayDeque<GpuBuffer> freeReadbackBuffers = new ArrayDeque<>();
    private int previousCursor;
    private int previousSpeedIndex;

    /** How an export ended: complete, complete but silent because the sound could not be added, or not at all. */
    private enum Outcome {
        DONE, DONE_WITHOUT_SOUND, FAILED
    }

    private VideoExporter(ReplaySession session, int startTick, int endTick, int fps, Path output, boolean pngSequence, Process process,
                          int width, int height, boolean previousHideGui, boolean previousPauseOnLostFocus) {
        this.session = session;
        this.startTick = startTick;
        this.endTick = endTick;
        this.fps = fps;
        this.output = output;
        this.pngSequence = pngSequence;
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

    /** First tick of the exported range. */
    public int startTick() {
        return startTick;
    }

    /** Last tick of the exported range. */
    public int endTick() {
        return endTick;
    }

    public float progress() {
        long total = (long) (endTick - startTick) * fps / 20 + 1;
        return Math.min(1.0F, (float) frames / Math.max(1L, total));
    }

    /** The video file, or the folder of a PNG sequence. */
    public Path output() {
        return output;
    }

    /** Frames written so far. */
    public int frames() {
        return frames;
    }

    /** Completes once the output is fully written (FFmpeg ended, sound added): true on success. */
    public java.util.concurrent.CompletableFuture<Boolean> completion() {
        return completion;
    }

    /**
     * Starts an export of the camera path between two ticks with the settings of the config file. Returns an error
     * message, or null on success.
     */
    public static Component start(ReplaySession session, int startTick, int endTick, String fileName) {
        return start(session, startTick, endTick, fileName, Settings.fromConfig());
    }

    /**
     * Saves the frame at a tick as one PNG named {@code <name>_still_<tick>.png} in the export folder, rendered like
     * a video frame (export size, effects, letterbox, overlays). It runs a one frame PNG sequence export and keeps its
     * frame. Returns an error message, or null on success.
     */
    public static Component saveStill(ReplaySession session, int tick, String fileName, Settings settings) {
        int duration = session.playback().duration();
        int at = Math.max(0, Math.min(tick, duration - 1));
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.endsWith(".mp4")) {
            safeName = safeName.substring(0, safeName.length() - 4);
        }
        if (safeName.isBlank()) {
            safeName = "export";
        }
        String base = safeName + "_still_" + at;
        Path target = ReplayStorage.exportRoot().resolve(base + ".png");
        for (int n = 2; java.nio.file.Files.exists(target); n++) {
            target = ReplayStorage.exportRoot().resolve(base + "_" + n + ".png");
        }
        Settings png = new Settings(settings.fps(), settings.crf(), settings.resolution(), settings.width(), settings.height(), true);
        Component error = start(session, at, at + 1, base + "_frames", png);
        if (error == null && current != null) {
            current.still = target;
        }
        return error;
    }

    /** Starts an export of the camera path between two ticks. Returns an error message, or null on success. */
    public static Component start(ReplaySession session, int startTick, int endTick, String fileName, Settings settings) {
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
        int fps = settings.fps();
        boolean png = settings.pngSequence();
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.endsWith(".mp4")) {
            safeName = safeName.substring(0, safeName.length() - 4);
        }
        if (safeName.isBlank()) {
            safeName = "export";
        }
        // A PNG sequence is a folder named like the video would be, without the extension.
        String extension = png ? "" : ".mp4";
        Path output = ReplayStorage.exportRoot().resolve(safeName + extension);
        for (int n = 2; java.nio.file.Files.exists(output); n++) {
            // Never overwrite an earlier export.
            output = ReplayStorage.exportRoot().resolve(safeName + "_" + n + extension);
        }
        if (png) {
            try {
                java.nio.file.Files.createDirectories(output);
            } catch (IOException e) {
                JustReplay.LOGGER.error("Cannot create the folder {}", output, e);
                return Component.literal("Cannot create " + output);
            }
        }

        // Render at the chosen size when the GPU allows it, otherwise at window size with FFmpeg scaling.
        boolean custom = settings.width() > 0 && settings.height() > 0;
        boolean window = !custom && settings.resolution().isWindow();
        int outWidth = window ? windowWidth : custom ? settings.width() : settings.resolution().width();
        int outHeight = window ? windowHeight : custom ? settings.height() : settings.resolution().height();
        boolean trueSize = window || ExportRenderSize.fits(outWidth, outHeight);
        int renderWidth = trueSize ? outWidth : windowWidth;
        int renderHeight = trueSize ? outHeight : windowHeight;
        int crf = settings.crf();
        Process process;
        try {
            process = launch(output, png, fps, crf, renderWidth, renderHeight, trueSize ? 0 : outWidth, outHeight);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot start ffmpeg", e);
            deleteOutput(output, png, false);
            return Component.translatable("justreplay.export.error.ffmpeg", JRConfig.FFMPEG_PATH.get());
        }

        // The screen stays open until FFmpeg runs for good, so an error can still be shown on it.
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
                process = launch(output, png, fps, crf, renderWidth, renderHeight, outWidth, outHeight);
            } catch (IOException e) {
                JustReplay.LOGGER.error("Cannot start ffmpeg", e);
                ExportRenderSize.unlock();
                deleteOutput(output, png, false);
                return Component.translatable("justreplay.export.error.ffmpeg", JRConfig.FFMPEG_PATH.get());
            }
        }

        boolean previousHideGui = mc.options.hideGui;
        boolean previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.getSoundManager().stop(); // live sounds would be heard out of sync; the track is rebuilt offline
        mc.options.hideGui = true;
        mc.options.pauseOnLostFocus = false;
        mc.setScreen(null);

        VideoExporter exporter = new VideoExporter(session, startTick, endTick, fps, output, png, process, renderWidth, renderHeight,
                previousHideGui, previousPauseOnLostFocus);
        Playback playback = session.playback();
        exporter.previousCursor = playback.cursor();
        exporter.previousSpeedIndex = playback.speedIndex();
        current = exporter;
        com.vyrriox.justreplay.compat.ReplayCompat.onExportStart();
        exporter.writerThread.start();
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
     * cropped. The output is H.264 tagged BT.709, limited range, or with {@code png} one RGB PNG per frame in the
     * {@code output} folder, uncropped.
     */
    private static Process launch(Path output, boolean png, int fps, int crf, int renderWidth, int renderHeight, int scaleWidth,
                                  int scaleHeight) throws IOException {
        String scale = "scale=" + scaleWidth + ":" + scaleHeight + ":force_original_aspect_ratio=increase:flags=lanczos,crop=" + scaleWidth + ":" + scaleHeight;
        List<String> command = new ArrayList<>(List.of(
                JRConfig.FFMPEG_PATH.get(), "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", renderWidth + "x" + renderHeight, "-r", Integer.toString(fps), "-i", "-"));
        if (png) {
            command.addAll(List.of("-vf", (scaleWidth > 0 ? scale + "," : "") + "format=rgb24",
                    "-c:v", "png", "-start_number", "0", output.resolve("frame_%05d.png").toString()));
        } else {
            String size = scaleWidth > 0 ? scale : "crop=trunc(iw/2)*2:trunc(ih/2)*2";
            // The YUV conversion happens in this scale filter, so it uses the BT.709 matrix the stream is tagged with.
            String filter = size + ",scale=iw:ih:flags=lanczos:out_color_matrix=bt709:out_range=tv,format=yuv420p";
            command.addAll(List.of(
                    "-vf", filter,
                    "-c:v", "libx264", "-preset", "medium", "-crf", Integer.toString(crf),
                    "-pix_fmt", "yuv420p", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                    "-movflags", "+faststart", output.toString()));
        }
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.resolveSibling(output.getFileName() + ".log").toFile())
                .start();
    }

    private TimerStep timerStep() {
        timerPrepared = false;
        if (draining) {
            return TimerStep.HOLD;
        }
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
        if (pendingReadbacks > 0 && System.nanoTime() - lastReadbackNanos > READBACK_TIMEOUT_NANOS) {
            JustReplay.LOGGER.error("The GPU did not deliver {} frames in time, stopping the export", pendingReadbacks);
            finish(false);
            return;
        }
        if (draining) {
            if (pendingReadbacks == 0) {
                finish(true);
            }
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
        if (pendingReadbacks >= MAX_PENDING_READBACKS) {
            // The GPU is behind: the clock does not advance, this frame is rendered again and captured next time.
            return;
        }
        if (!requestReadback(target)) {
            finish(false);
            return;
        }
        frames++;
        Camera camera = mc.gameRenderer.getMainCamera();
        audio.onFrame(camera.position().x, camera.position().y, camera.position().z, camera.yRot());
        com.vyrriox.justreplay.compat.ReplayCompat.onExportFrame(audio, frames - 1, fps, camera.position().x, camera.position().y,
                camera.position().z, camera.yRot(), partialTick);
        Playback playback = session.playback();
        boolean reachedEnd = (long) (frames - 1) * 20 >= (long) (endTick - startTick) * fps;
        if (reachedEnd || playback.isPaused() && playback.cursor() >= playback.duration()) {
            // The last frames are still on their way; finish once they all reached the writer.
            draining = true;
        }
    }

    /**
     * Copies the finished frame to a staging buffer. The copy is ordered in the GPU command stream, so later
     * rendering cannot change it, and fenced tasks run first in, first out, so frames reach FFmpeg in order.
     */
    private boolean requestReadback(RenderTarget target) {
        GpuTexture color = target.getColorTexture();
        if (color == null || color.getFormat().pixelSize() != 4) {
            JustReplay.LOGGER.error("Cannot capture a frame from the render target: {}", color == null ? "no color texture" : color.getFormat());
            return false;
        }
        int rowBytes = width * 4;
        GpuBuffer buffer = freeReadbackBuffers.poll();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(() -> "Just Replay export frame",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) rowBytes * height);
        }
        GpuBuffer staging = buffer;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        pendingReadbacks++;
        lastReadbackNanos = System.nanoTime();
        encoder.copyTextureToBuffer(color, staging, 0L, () -> onReadback(encoder, staging), 0);
        return true;
    }

    /**
     * Runs on the render thread inside the pending GPU tasks, so it must not throw and must not end the export
     * itself (that resizes render targets): it only flags a failure, the next frame ends the export.
     */
    private void onReadback(CommandEncoder encoder, GpuBuffer staging) {
        pendingReadbacks--;
        lastReadbackNanos = System.nanoTime();
        try {
            if (!finished && !failed) {
                byte[] frame;
                try (GpuBuffer.MappedView view = encoder.mapBuffer(staging, true, false)) {
                    frame = flipOpaque(view.data());
                }
                // Never block forever: if ffmpeg died the writer stops draining the queue.
                while (!queue.offer(frame, 100, TimeUnit.MILLISECONDS)) {
                    if (failed || !process.isAlive()) {
                        failed = true;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            failed = true;
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failed = true;
            JustReplay.LOGGER.error("Cannot read an export frame back from the GPU", t);
        }
        if (finished) {
            staging.close();
        } else {
            freeReadbackBuffers.add(staging);
        }
    }

    /** The texture is bottom row first; FFmpeg wants top row first and an opaque image. */
    private byte[] flipOpaque(ByteBuffer pixels) {
        int rowBytes = width * 4;
        byte[] frame = new byte[rowBytes * height];
        for (int y = 0; y < height; y++) {
            pixels.get(y * rowBytes, frame, (height - y - 1) * rowBytes, rowBytes);
        }
        for (int i = 3; i < frame.length; i += 4) {
            frame[i] = (byte) 0xFF;
        }
        return frame;
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
        // A readback still in flight closes its own buffer when it lands.
        freeReadbackBuffers.forEach(GpuBuffer::close);
        freeReadbackBuffers.clear();
        com.vyrriox.justreplay.compat.ReplayCompat.onExportEnd();
        Minecraft mc = Minecraft.getInstance();
        try {
            ExportRenderSize.unlock();
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot restore the render size after an export", t);
        }
        mc.options.hideGui = previousHideGui;
        mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
        session.playback().setPaused(true);
        Thread waiter = new Thread(() -> {
            Outcome outcome = Outcome.FAILED;
            try {
                outcome = waitForOutput(success);
            } catch (Throwable t) {
                JustReplay.LOGGER.error("The export could not be finished", t);
            } finally {
                conclude(mc, outcome);
            }
        }, "JustReplay export finisher");
        waiter.setDaemon(true);
        waiter.start();
    }

    /** Runs on the finisher thread: lets FFmpeg end, then completes the output. */
    private Outcome waitForOutput(boolean success) throws InterruptedException {
        boolean ok = success;
        // A full queue is normal at the end: the game renders faster than FFmpeg encodes. The end marker
        // waits here, off the render thread, for room; only a dead FFmpeg makes the export give up.
        while (!queue.offer(END, 1, TimeUnit.SECONDS)) {
            if (failed || !process.isAlive()) {
                failed = true;
                queue.clear();
                queue.offer(END);
                ok = false;
                break;
            }
        }
        writerThread.join();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            JustReplay.LOGGER.error("FFmpeg did not end after the last frame, stopping it");
            return Outcome.FAILED;
        }
        if (!ok || process.exitValue() != 0) {
            return Outcome.FAILED;
        }
        if (still != null) {
            return keepStill() ? Outcome.DONE : Outcome.FAILED;
        }
        audio.awaitVoices();
        if (audio.isEmpty()) {
            return Outcome.DONE;
        }
        return pngSequence ? writeAudioTrack() : addAudio();
    }

    /** Always runs last: removes what a failed export left, tells the player and completes the future. */
    private void conclude(Minecraft mc, Outcome outcome) {
        boolean success = outcome != Outcome.FAILED;
        try {
            if (!success) {
                stopProcess(process);
                deleteOutput(output, pngSequence, !cancelled);
            }
            Path shown = still != null && success ? still : output;
            mc.execute(() -> {
                String title = switch (outcome) {
                    case DONE -> still != null ? "justreplay.export.still.done" : "justreplay.export.done";
                    case DONE_WITHOUT_SOUND -> "justreplay.export.done.nosound";
                    case FAILED -> cancelled ? "justreplay.export.cancelled" : "justreplay.export.failed";
                };
                SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable(title), Component.literal(shown.getFileName().toString()));
                if (still != null && success && mc.player != null) {
                    mc.player.sendSystemMessage(Component.translatable("justreplay.export.still.saved", shown.toString()));
                }
                restorePlayback(success);
            });
            JustReplay.LOGGER.info("Export {} ({} frames): {}", outcome == Outcome.DONE ? "finished"
                    : success ? "finished without sound" : "failed", frames, output);
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot report the end of the export", t);
        } finally {
            completion.complete(success);
        }
    }

    /**
     * Back to the editing state after a failed or cancelled export or a still; a finished video stays on its last
     * frame. Nothing happens when the replay was closed or another export has started.
     */
    private void restorePlayback(boolean success) {
        if (ReplaySession.current() != session || current != null || success && still == null) {
            return;
        }
        Playback playback = session.playback();
        playback.setSpeedIndex(previousSpeedIndex);
        // A still goes back on the frame it was taken from.
        playback.seek(success ? startTick : previousCursor);
    }

    private static void stopProcess(Process process) {
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Frees the name of an export that did not finish: removes its file or frame folder, and its log unless asked to keep it. */
    private static void deleteOutput(Path output, boolean png, boolean keepLog) {
        try {
            if (png && java.nio.file.Files.isDirectory(output)) {
                try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(output)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        java.nio.file.Files.deleteIfExists(file);
                    }
                }
            }
            java.nio.file.Files.deleteIfExists(output);
            if (!keepLog) {
                java.nio.file.Files.deleteIfExists(output.resolveSibling(output.getFileName() + ".log"));
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            JustReplay.LOGGER.warn("Could not remove the unfinished export {}", output, e);
        }
    }

    /** Moves the first frame to the still file and removes the frame folder. */
    private boolean keepStill() {
        try {
            java.nio.file.Files.move(output.resolve("frame_00000.png"), still);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot save the still {}", still, e);
            return false;
        }
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(output)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                java.nio.file.Files.deleteIfExists(file);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            JustReplay.LOGGER.warn("Could not empty the frame folder {}", output, e);
        }
        try {
            java.nio.file.Files.deleteIfExists(output);
            java.nio.file.Files.deleteIfExists(output.resolveSibling(output.getFileName() + ".log"));
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Could not remove the frame folder {}", output, e);
        }
        return true;
    }

    /** Mixes the logged sounds into {@code audio.wav} beside the frames of a PNG sequence. */
    private Outcome writeAudioTrack() {
        Path wav = output.resolve("audio.wav");
        try {
            audio.writeWav(wav, frames);
            return Outcome.DONE;
        } catch (Exception | Error e) {
            JustReplay.LOGGER.warn("Could not write the audio track, the frames are kept without sound", e);
            try {
                java.nio.file.Files.deleteIfExists(wav);
            } catch (IOException ignored) {
            }
            return Outcome.DONE_WITHOUT_SOUND;
        }
    }

    /** Mixes the logged sounds and muxes them into the finished video (video stream copied, AAC audio). */
    private Outcome addAudio() {
        Path wav = output.resolveSibling(output.getFileName() + ".wav");
        Path muxed = output.resolveSibling(output.getFileName() + ".tmp.mp4");
        Process mux = null;
        try {
            audio.writeWav(wav, frames);
            mux = new ProcessBuilder(JRConfig.FFMPEG_PATH.get(), "-y", "-i", output.toString(), "-i", wav.toString(),
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", muxed.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.resolveSibling(output.getFileName() + ".audio.log").toFile())
                    .start();
            if (!mux.waitFor(10, TimeUnit.MINUTES) || mux.exitValue() != 0) {
                JustReplay.LOGGER.warn("Could not add the audio track, the video is kept without sound");
                return Outcome.DONE_WITHOUT_SOUND;
            }
            java.nio.file.Files.move(muxed, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Outcome.DONE;
        } catch (Exception | Error e) {
            JustReplay.LOGGER.warn("Could not add the audio track, the video is kept without sound", e);
            return Outcome.DONE_WITHOUT_SOUND;
        } finally {
            if (mux != null) {
                // A hung mux holds the temp files, which Windows then refuses to delete.
                stopProcess(mux);
            }
            try {
                java.nio.file.Files.deleteIfExists(wav);
                java.nio.file.Files.deleteIfExists(muxed);
            } catch (IOException ignored) {
            }
        }
    }

    /** Sounds never play live during an export; they are logged for the audio track instead. */
    public static boolean onSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
        VideoExporter e = current;
        if (e == null) {
            return true;
        }
        e.audio.onSound(sound, e.trackSample(), e.phase == Phase.CAPTURE);
        return false;
    }

    /** A sound was stopped by the game (see the sound manager mixin). */
    public static void onSoundStopped(net.minecraft.client.resources.sounds.SoundInstance sound) {
        VideoExporter e = current;
        if (e != null) {
            e.audio.onStop(sound, e.trackSample());
        }
    }

    /** Sounds were stopped by name and source, either of which may be null (see the sound manager mixin). */
    public static void onSoundsStopped(net.minecraft.resources.Identifier name, net.minecraft.sounds.SoundSource source) {
        VideoExporter e = current;
        if (e != null) {
            e.audio.onStop(name, source, e.trackSample());
        }
    }

    /** Sample of the audio track being reached; before the first frame it is negative, counted in pre-roll ticks. */
    private long trackSample() {
        return phase == Phase.CAPTURE ? (long) frames * ExportAudio.RATE / fps
                : Math.min(0L, (long) session.playback().cursor() - captureCursor) * ExportAudio.RATE / 20;
    }

    public static void cancel() {
        VideoExporter e = current;
        if (e != null) {
            e.cancelled = true;
            e.failed = true;
            e.finish(false);
        }
    }
}
