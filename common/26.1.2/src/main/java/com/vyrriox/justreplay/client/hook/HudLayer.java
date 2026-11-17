package com.vyrriox.justreplay.client.hook;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A HUD layer drawn every frame. Loader neutral stand in for the loader's own layer type.
 *
 * @author vyrriox
 */
@FunctionalInterface
public interface HudLayer {
    void render(GuiGraphicsExtractor graphics, DeltaTracker delta);
}
