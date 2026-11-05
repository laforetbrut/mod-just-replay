package com.vyrriox.justreplay.mixin;

import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Tells the full pause menu from the short one shown on a screen opened by a key, which has no getter in this version.
 *
 * @author vyrriox
 */
@Mixin(PauseScreen.class)
public interface PauseScreenAccessor {
    @Accessor("showPauseMenu")
    boolean justreplay$showsPauseMenu();
}
