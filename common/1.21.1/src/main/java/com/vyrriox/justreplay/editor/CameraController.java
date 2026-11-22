package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the camera every rendered frame, so the motion is as smooth as the frame rate allows, while the preview
 * or an export is active: the cut track (with its blends) when it has cuts, else the selected camera. Roll and
 * field of view are applied through the NeoForge viewport events.
 *
 * @author vyrriox
 */
public final class CameraController {
    private static double[] lastState;
    /** Camera put in place of the camera path for a frame of the MCP tools, or null. */
    private static double[] override;

    private CameraController() {
    }

    /** Replay time shown by the current frame, in ticks. */
    public static double frameTime(ReplaySession session, float partialTick) {
        Playback playback = session.playback();
        if (playback.isPaused() || playback.isHolding()) {
            return playback.cursor();
        }
        int step = Math.max(1, playback.lastStep());
        return Math.max(0, playback.cursor() - step + partialTick * step);
    }

    /**
     * Holds the camera at {@code state} (as a camera keyframe gives it: position of the feet, yaw, pitch, roll and
     * field of view) at every frame, in place of the camera path, until {@link #clearOverride}.
     */
    public static void override(double[] state) {
        override = state == null ? null : state.clone();
    }

    public static void clearOverride() {
        override = null;
    }

    public static void onRenderFrame(float partialTick) {
        lastState = null;
        ReplaySession session = ReplaySession.current();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || !ReplaySession.isRunning() || override == null && !session.isCameraPathActive() || mc.player == null) {
            return;
        }
        double[] state;
        if (override != null) {
            state = override;
        } else {
            double time = frameTime(session, partialTick);
            state = CameraRig.sampleProject(session.project(), time, partialTick, true);
            // A picture transition reached without its outgoing frame shows the outgoing camera for one frame.
            double[] outgoing = com.vyrriox.justreplay.editor.fx.FxEngine.outgoingState(session.project(), time, partialTick);
            if (outgoing != null) {
                state = outgoing;
            }
        }
        if (state == null) {
            return;
        }
        LocalPlayer player = mc.player;
        Playback.placeCamera(player, new Vec3(state[0], state[1], state[2]), (float) state[3], (float) state[4]);
        lastState = state;
    }

    public static Float roll() {
        return lastState == null ? null : (float) lastState[5];
    }

    public static Double fov() {
        return lastState == null ? null : lastState[6];
    }
}
