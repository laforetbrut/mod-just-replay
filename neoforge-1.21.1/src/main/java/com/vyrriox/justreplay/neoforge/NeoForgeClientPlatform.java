package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * {@link ClientPlatform} on NeoForge. Created by name.
 *
 * @author vyrriox
 */
public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override
    public void openConfigScreen(Screen parent) {
        Minecraft.getInstance().setScreen(new JRConfigScreen(parent));
    }
}
