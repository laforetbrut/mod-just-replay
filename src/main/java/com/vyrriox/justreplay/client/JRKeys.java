package com.vyrriox.justreplay.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings of Just Replay. Playback keys only act while a replay is open.
 *
 * @author vyrriox
 */
public final class JRKeys {
    private static final String CATEGORY = "key.categories.justreplay";

    /** Playback keys only act inside a replay, so they never steal keys from other mods in a normal game. */
    public static final IKeyConflictContext IN_REPLAY = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return com.vyrriox.justreplay.replay.ReplaySession.isRunning() && KeyConflictContext.IN_GAME.isActive();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };

    public static final KeyMapping RECORD = new KeyMapping("key.justreplay.record", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    public static final KeyMapping EDITOR = key("editor", GLFW.GLFW_KEY_G);
    public static final KeyMapping PLAY_PAUSE = key("play_pause", GLFW.GLFW_KEY_J);
    public static final KeyMapping SEEK_BACK = key("seek_back", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping SEEK_FORWARD = key("seek_forward", GLFW.GLFW_KEY_RIGHT);
    public static final KeyMapping SPEED_UP = key("speed_up", GLFW.GLFW_KEY_UP);
    public static final KeyMapping SPEED_DOWN = key("speed_down", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping CAMERA_KEYFRAME = key("camera_keyframe", GLFW.GLFW_KEY_K);
    public static final KeyMapping CAMERA_PATH = key("camera_path", GLFW.GLFW_KEY_O);
    public static final KeyMapping MARKER = key("marker", GLFW.GLFW_KEY_M);
    public static final KeyMapping TOGGLE_HUD = key("toggle_hud", GLFW.GLFW_KEY_H);
    public static final KeyMapping RECORDER_VIEW = key("recorder_view", GLFW.GLFW_KEY_V);
    /** Unbound by default: sneaking already leaves the spectated entity. */
    public static final KeyMapping STOP_SPECTATING = key("stop_spectating", GLFW.GLFW_KEY_UNKNOWN);
    /** Live switching: key N makes camera N live from the play head, like a multi-camera editor. */
    public static final KeyMapping[] CUT_TO = new KeyMapping[9];

    static {
        for (int i = 0; i < CUT_TO.length; i++) {
            CUT_TO[i] = key("cut_" + (i + 1), GLFW.GLFW_KEY_1 + i);
        }
    }

    private JRKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.justreplay." + name, IN_REPLAY, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : new KeyMapping[]{RECORD, EDITOR, PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, SPEED_UP, SPEED_DOWN,
                CAMERA_KEYFRAME, CAMERA_PATH, MARKER, TOGGLE_HUD, RECORDER_VIEW, STOP_SPECTATING}) {
            event.register(key);
        }
        for (KeyMapping key : CUT_TO) {
            event.register(key);
        }
    }

    /** Index of the live switching key matching a key press, or -1. */
    public static int cutKeyIndex(int keyCode, int scanCode) {
        for (int i = 0; i < CUT_TO.length; i++) {
            if (CUT_TO[i].matches(keyCode, scanCode)) {
                return i;
            }
        }
        return -1;
    }
}
