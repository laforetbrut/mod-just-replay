package com.vyrriox.justreplay;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Constants shared by the whole mod, the plain ones taken from {@link JustReplayCore}. All features are client side;
 * the mod is inert on a dedicated server. Each loader has its own entry class in
 * {@code com.vyrriox.justreplay.<loader>}.
 *
 * @author vyrriox
 */
public final class JustReplay {
    public static final String MOD_ID = JustReplayCore.MOD_ID;
    public static final Logger LOGGER = JustReplayCore.LOGGER;

    private JustReplay() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
