package com.vyrriox.justreplay.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.mcp.McpArgs;
import com.vyrriox.justreplay.mcp.McpCamera;
import com.vyrriox.justreplay.mcp.McpObserver;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Frames drawn for the MCP observation tools, kept in memory and never written to a file. A video frame is drawn by
 * the video export itself ({@link VideoExporter#grab}): render size, pre-roll, effects, letterbox and overlays, from
 * the project's camera path or from a camera put in its place. A window frame is the window as the player sees it,
 * editor and HUD included, read once the chunks are drawn. Either way the play head with its speed and pause, the
 * screen, the camera and the spectated entity are put back afterwards; the editor is never closed, so nothing is
 * saved.
 *
 * @author vyrriox
 */
public final class FrameGrab {
    /** A window frame waits at least this many frames, so that a jump or a moved camera shows. */
    private static final int WINDOW_MIN_FRAMES = 3;
    /** A window frame waits at most this many frames for the chunks. */
    private static final int WINDOW_MAX_FRAMES = 600;
    /** Frames with every chunk drawn before a window frame is read. */
    private static final int WINDOW_READY_FRAMES = 2;
    private static FrameGrab window;
    /** The video frame being drawn by the export, or null. */
    private static FrameGrab video;
    /** A frame drawn or given up, put back at the next client tick rather than inside the frame being drawn. */
    private static FrameGrab done;
    private static long grabbed;

    final McpObserver.FrameRequest request;
    final CompletableFuture<McpObserver.Frame> future = new CompletableFuture<>();
    private final ReplaySession session;
    private final Screen screen;
    private final int cursor;
    private final boolean paused;
    private final int speedIndex;
    private final Vec3 position;
    private final float yaw;
    private final float pitch;
    private final UUID spectated;
    private final boolean spectatedRecorder;
    private int frames;
    private int ready;
    private byte[] pixels;
    private int pixelWidth;
    private int pixelHeight;
    private McpCamera seen;
    private boolean ended;
    private boolean success;

    private FrameGrab(ReplaySession session, McpObserver.FrameRequest request) {
        Minecraft mc = Minecraft.getInstance();
        this.session = session;
        this.request = request;
        this.screen = mc.screen;
        Playback playback = session == null ? null : session.playback();
        this.cursor = playback == null ? 0 : playback.cursor();
        this.paused = playback == null || playback.isPaused();
        this.speedIndex = playback == null ? 0 : playback.speedIndex();
        this.position = mc.player == null ? null : mc.player.position();
        this.yaw = mc.player == null ? 0.0F : mc.player.getYRot();
        this.pitch = mc.player == null ? 0.0F : mc.player.getXRot();
        this.spectated = session == null ? null : session.spectate().targetId();
        this.spectatedRecorder = session != null && session.spectate().isFollowingRecorder();
    }

    /** A window frame is being drawn, or a frame is waiting to be put back. */
    public static boolean isActive() {
        return window != null || done != null;
    }

    /** Client thread, every tick: a frame that was drawn puts the replay back and hands its picture over. */
    public static void tick() {
        // A caller that gave up is noticed here too: a minimised window may draw no frame to notice it.
        if (window != null && window.abandoned()) {
            window.finished(false);
        }
        if (video != null && video.abandoned() && VideoExporter.isActive()) {
            VideoExporter.cancel();
        }
        FrameGrab g = done;
        if (g != null) {
            done = null;
            g.complete();
        }
    }

    /** Frames handed to the MCP tools since the game started, for the self test. */
    public static long grabbed() {
        return grabbed;
    }

    /**
     * Game thread: starts drawing the frame asked for. The future completes on the game thread.
     *
     * @throws McpArgs.Invalid when a frame or an export is already being drawn, or a video frame has no replay
     */
    public static CompletableFuture<McpObserver.Frame> start(McpObserver.FrameRequest request) throws McpArgs.Invalid {
        if (VideoExporter.isActive() || isActive()) {
            throw new McpArgs.Invalid("A video export or another frame is being drawn; try again once it is done.");
        }
        Minecraft mc = Minecraft.getInstance();
        ReplaySession session = ReplaySession.isRunning() ? ReplaySession.current() : null;
        if (!request.ui()) {
            if (session == null) {
                throw new McpArgs.Invalid("No replay is open in the game.");
            }
            FrameGrab g = new FrameGrab(session, request);
            g.aim();
            // The screen is set aside without closing it: closing the editor would save the project.
            mc.screen = null;
            Component error = VideoExporter.grab(session, g);
            if (error != null) {
                g.restore();
                throw new McpArgs.Invalid(error.getString());
            }
            video = g;
            return g.future;
        }
        FrameGrab g = new FrameGrab(session, request);
        if (session != null && request.tick() != session.playback().cursor()) {
            session.playback().seek(request.tick());
            session.playback().setPaused(true);
        }
        g.aim();
        window = g;
        return g.future;
    }

    /** Puts the camera asked for in place, through the camera controller, for as long as the frame takes. */
    private void aim() {
        McpCamera c = request.camera();
        if (c != null && session != null) {
            // A spectated entity would hold the view; it is followed again afterwards.
            session.spectate().stop(Minecraft.getInstance());
            CameraController.override(new double[]{c.x(), c.y() - CameraRig.EYE_HEIGHT, c.z(), c.yaw(), c.pitch(), c.roll(), c.fov()});
        }
    }

    /** Called after every rendered frame: a window frame is read once its frame is drawn. */
    public static void onFrameRendered() {
        FrameGrab g = window;
        if (g != null) {
            g.windowFrame();
        }
    }

    private void windowFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (future.isCancelled()) {
            finished(false);
            return;
        }
        if (session != null && ReplaySession.current() != session) {
            failed("The replay was closed before the frame was drawn.");
            return;
        }
        frames++;
        ready = mc.level == null || mc.levelRenderer.hasRenderedAllSections() ? ready + 1 : 0;
        if (frames < WINDOW_MIN_FRAMES || ready < WINDOW_READY_FRAMES && frames < WINDOW_MAX_FRAMES) {
            return;
        }
        RenderTarget target = mc.getMainRenderTarget();
        try (NativeImage image = Screenshot.takeScreenshot(target)) {
            keep(rgba(image), image.getWidth(), image.getHeight());
            saw(camera(mc, "current"));
            finished(true);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot read the window for an MCP frame", t);
            failed("The window could not be read: " + t);
        }
    }

    /** The pixels of a picture the game read, as the video export gives them to FFmpeg: RGBA, top row first. */
    static byte[] rgba(NativeImage image) {
        int[] pixels = image.getPixelsRGBA();
        ByteBuffer bytes = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(pixels);
        return bytes.array();
    }

    /** The camera the frame shows. */
    public static McpCamera camera(Minecraft mc, String source) {
        Camera c = mc.gameRenderer.getMainCamera();
        Float roll = CameraController.roll();
        Double fov = CameraController.fov();
        return new McpCamera(c.getPosition().x, c.getPosition().y, c.getPosition().z, c.getYRot(), c.getXRot(),
                roll == null ? 0.0 : roll, fov == null ? mc.options.fov().get() : fov, source);
    }

    /** The captured picture of this frame. */
    void keep(byte[] rgba, int width, int height) {
        pixels = rgba;
        pixelWidth = width;
        pixelHeight = height;
    }

    /** The camera the captured frame was drawn from. */
    void saw(McpCamera camera) {
        seen = camera;
    }

    /** The frame was drawn ({@code success}) or not: at the next client tick everything is put back. */
    void finished(boolean success) {
        if (ended) {
            return;
        }
        ended = true;
        this.success = success;
        if (window == this) {
            window = null;
        }
        if (video == this) {
            video = null;
        }
        done = this;
    }

    /** Puts everything back and gives the caller the picture, or why there is none. */
    private void complete() {
        try {
            restore();
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot put the replay back after an MCP frame", t);
        }
        if (success && pixels != null) {
            grabbed++;
            future.complete(new McpObserver.Frame(pixels, pixelWidth, pixelHeight, request.tick(), seen));
        } else if (!future.isDone()) {
            future.completeExceptionally(new McpArgs.Invalid("The frame could not be drawn: it was cancelled or the replay was closed."));
        }
    }

    private void failed(String message) {
        if (!future.isDone()) {
            future.completeExceptionally(new McpArgs.Invalid(message));
        }
        finished(false);
    }

    /** True when the caller gave up waiting: the frame stops. */
    boolean abandoned() {
        return future.isCancelled();
    }

    private void restore() {
        Minecraft mc = Minecraft.getInstance();
        CameraController.clearOverride();
        boolean alive = session != null && ReplaySession.current() == session;
        if (alive) {
            Playback playback = session.playback();
            if (playback.cursor() != cursor) {
                playback.seek(cursor);
            }
            playback.setSpeedIndex(speedIndex);
            playback.setPaused(paused);
            if (mc.player != null && position != null) {
                Playback.placeCamera(mc.player, position, yaw, pitch);
            }
            if (spectated != null && !session.spectate().isActive() && mc.level != null) {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (spectated.equals(e.getUUID())) {
                        session.spectate().start(mc, e, spectatedRecorder);
                        break;
                    }
                }
            }
        }
        if (!request.ui() && alive && screen != null && mc.screen == null) {
            mc.screen = screen;
            mc.mouseHandler.releaseMouse();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            if (screen.width != w || screen.height != h) {
                screen.resize(mc, w, h);
            }
        }
    }
}
