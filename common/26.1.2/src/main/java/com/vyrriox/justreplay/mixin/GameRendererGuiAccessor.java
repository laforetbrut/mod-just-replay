package com.vyrriox.justreplay.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The interface renderer of the game, which draws the render veil in a pass of its own after the frame is captured.
 *
 * @author vyrriox
 */
@Mixin(GameRenderer.class)
public interface GameRendererGuiAccessor {
    @Accessor("guiRenderer")
    GuiRenderer justreplay$guiRenderer();
}
