package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.mixin.MinecraftTimeAccessor;
import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.Minecraft;

/**
 * Partial tick of the frame being drawn.
 *
 * @author vyrriox
 */
public final class Frames {
    private Frames() {
    }

    /** The partial tick of the world: 1 while the level is frozen, since frozen things hold their last tick. */
    public static float partialTick() {
        return TimeControl.isFrozen() ? 1.0F : realPartialTick();
    }

    /** The partial tick of what keeps ticking through a freeze: the players and the camera they carry. */
    public static float realPartialTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.isPaused() ? ((MinecraftTimeAccessor) mc).justreplay$pausePartialTick() : mc.getFrameTime();
    }
}
