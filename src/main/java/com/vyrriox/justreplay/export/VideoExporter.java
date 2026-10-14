package com.vyrriox.justreplay.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.format.ReplayStorage;
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
 *
 * @author vyrriox
 */
public final class VideoExporter {
    private static final byte[] END = new byte[0];
    private static VideoExporter current;

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
    private int warmup;
    private boolean capturing;
    private int frames;
    private final ExportAudio audio;
    private volatile boolean failed;
    private boolean finished;

    private VideoExporter(ReplaySession session, int startTick, int endTick, int fps, Path output, Process process, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        this.session = session;
        this.startTick = startTick;
        this.endTick = endTick;
        this.fps = fps;
        this.output = output;
        this.process = process;
        this.stdin = process.getOutputStream();
        this.width = width;
        this.height = height;
        this.warmup = JRConfig.EXPORT_WARMUP_FRAMES.get();
        this.previousHideGui = mc.options.hideGui;
        this.previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
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

    public static float ticksPerFrame() {
        VideoExporter e = current;
        return e == null || !e.capturing ? 0.0F : 20.0F / e.fps;
    }

    public float progress() {
        return Math.min(1.0F, (float) frames / Math.max(1, (endTick - startTick) * fps / 20));
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
        int width = target.width;
        int height = target.height;
        int fps = JRConfig.EXPORT_FPS.get();
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
        safeName = output.getFileName().toString();
        List<String> command = new ArrayList<>(List.of(
                JRConfig.FFMPEG_PATH.get(), "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", Integer.toString(fps), "-i", "-",
                "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264", "-preset", "medium", "-crf", Integer.toString(JRConfig.EXPORT_CRF.get()),
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", output.toString()));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.resolveSibling(safeName + ".log").toFile())
                    .start();
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot start ffmpeg", e);
            return Component.translatable("justreplay.export.error.ffmpeg", JRConfig.FFMPEG_PATH.get());
        }
        VideoExporter exporter = new VideoExporter(session, startTick, endTick, fps, output, process, width, height);
        current = exporter;
        exporter.writerThread.start();
        mc.getSoundManager().stop(); // live sounds would be heard out of sync; the track is rebuilt offline
        mc.options.hideGui = true;
        mc.options.pauseOnLostFocus = false;
        mc.setScreen(null);
        session.playback().resetSpeed();
        session.playback().seek(startTick + 1);
        session.playback().setPaused(false);
        JustReplay.LOGGER.info("Exporting ticks {}..{} at {} fps, {}x{} to {}", startTick, endTick, fps, width, height, output);
        return null;
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
        if (!capturing) {
            if (warmup-- > 0) {
                return;
            }
            capturing = true;
        }
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width != width || target.height != height) {
            JustReplay.LOGGER.warn("Window resized during export, stopping");
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
        if (CameraController.frameTime(session, partialTick) >= endTick || session.playback().cursor() >= session.playback().duration()) {
            finish(true);
        }
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
        e.audio.onSound(event.getSound(), e.frames, e.capturing);
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
