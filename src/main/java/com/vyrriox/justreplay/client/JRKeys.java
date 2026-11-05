package com.vyrriox.justreplay.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.platform.KeyScope;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings of Just Replay. Playback keys only act while a replay is open.
 *
 * @author vyrriox
 */
public final class JRKeys {
    private static final String CATEGORY = "key.categories.justreplay";

    public static final KeyMapping RECORD = ClientPlatform.get().keyMapping("key.justreplay.record", KeyScope.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    /** Saves the instant replay buffer (the last minutes of play) as a replay. */
    public static final KeyMapping SAVE_BUFFER = ClientPlatform.get().keyMapping("key.justreplay.save_buffer", KeyScope.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
    /** Starts a recording that stops by itself after the configured minutes; unbound by default. */
    public static final KeyMapping RECORD_NEXT = ClientPlatform.get().keyMapping("key.justreplay.record_next", KeyScope.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
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
        return ClientPlatform.get().keyMapping("key.justreplay." + name, KeyScope.IN_REPLAY, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(java.util.function.Consumer<KeyMapping> registrar) {
        for (KeyMapping key : new KeyMapping[]{RECORD, EDITOR, PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, SPEED_UP, SPEED_DOWN,
                CAMERA_KEYFRAME, CAMERA_PATH, MARKER, TOGGLE_HUD, RECORDER_VIEW, STOP_SPECTATING, SAVE_BUFFER, RECORD_NEXT}) {
            registrar.accept(key);
        }
        for (KeyMapping key : CUT_TO) {
            registrar.accept(key);
        }
    }

    /** Index of the live switching key matching a key press, or -1. */
    public static int cutKeyIndex(int keyCode, int scanCode) {
        for (int i = 0; i < CUT_TO.length; i++) {
            if (CUT_TO[i].matches(new net.minecraft.client.input.KeyEvent(keyCode, scanCode, 0))) {
                return i;
            }
        }
        return -1;
    }
}
