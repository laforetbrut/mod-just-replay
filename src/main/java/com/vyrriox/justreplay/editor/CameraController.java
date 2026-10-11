package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the camera along the keyframed path, every rendered frame, so the motion is as smooth as the frame
 * rate allows. Roll and field of view are applied through the NeoForge viewport events.
 *
 * @author vyrriox
 */
public final class CameraController {
    private static double[] lastState;

    private CameraController() {
    }

    /** Replay time shown by the current frame, in ticks. */
    public static double frameTime(ReplaySession session, float partialTick) {
        Playback playback = session.playback();
        if (playback.isPaused()) {
            return playback.cursor();
        }
        return Math.max(0, playback.cursor() - 1 + partialTick);
    }

    public static void onRenderFrame(float partialTick) {
        lastState = null;
        ReplaySession session = ReplaySession.current();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || !ReplaySession.isRunning() || !session.isCameraPathActive() || mc.player == null) {
            return;
        }
        double[] state = Interpolation.camera(session.project().cameraKeyframes, frameTime(session, partialTick));
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
