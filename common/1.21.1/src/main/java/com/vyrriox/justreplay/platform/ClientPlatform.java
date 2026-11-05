package com.vyrriox.justreplay.platform;

import net.minecraft.client.gui.screens.Screen;

/**
 * What the mod needs from the loader on the physical client only. Never touch this class on a dedicated server.
 *
 * @author vyrriox
 */
public interface ClientPlatform {
    static ClientPlatform get() {
        return Holder.INSTANCE;
    }

    /** Opens the settings screen of the mod, going back to {@code parent} when it closes. */
    void openConfigScreen(Screen parent);

    /** Initialized on first use. */
    final class Holder {
        private static final ClientPlatform INSTANCE = LoaderGlue.create(ClientPlatform.class, "ClientPlatform");

        private Holder() {
        }
    }
}
