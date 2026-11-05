package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * {@link ClientPlatform} on NeoForge. Created by name.
 *
 * @author vyrriox
 */
public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override
    public void openConfigScreen(Screen parent) {
        ModList.get().getModContainerById(JustReplay.MOD_ID).ifPresent(container ->
                Minecraft.getInstance().setScreen(new ConfigurationScreen(container, parent)));
    }
}
