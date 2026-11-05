package com.vyrriox.justreplay;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * Constants shared by the whole mod. All features are client side; the mod is inert on a dedicated server. Each loader
 * has its own entry class in {@code com.vyrriox.justreplay.<loader>}.
 *
 * @author vyrriox
 */
public final class JustReplay {
    public static final String MOD_ID = "justreplay";
    public static final Logger LOGGER = LogUtils.getLogger();

    private JustReplay() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
