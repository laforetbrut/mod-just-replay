package com.vyrriox.justreplay.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings of Just Replay. Playback keys only act while a replay is open.
 *
 * @author vyrriox
 */
public final class JRKeys {
    private static final String CATEGORY = "key.categories.justreplay";

    public static final KeyMapping RECORD = key("record", GLFW.GLFW_KEY_F8);
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

    private JRKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.justreplay." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : new KeyMapping[]{RECORD, EDITOR, PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, SPEED_UP, SPEED_DOWN,
                CAMERA_KEYFRAME, CAMERA_PATH, MARKER, TOGGLE_HUD, RECORDER_VIEW}) {
            event.register(key);
        }
    }
}
