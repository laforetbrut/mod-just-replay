package com.vyrriox.justreplay.client.hook;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A layer of the HUD. This Minecraft version has no layered HUD of its own: each loader's glue draws these from its
 * overlay event or callback.
 *
 * @author vyrriox
 */
@FunctionalInterface
public interface HudLayer {
    void render(GuiGraphics graphics, float partialTick);
}
