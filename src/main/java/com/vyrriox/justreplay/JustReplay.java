package com.vyrriox.justreplay;

import com.mojang.logging.LogUtils;
import com.vyrriox.justreplay.config.JRConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Entry point of Just Replay. All features are client side; the mod is inert on a dedicated server.
 *
 * @author vyrriox
 */
@Mod(JustReplay.MOD_ID)
public final class JustReplay {
    public static final String MOD_ID = "justreplay";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JustReplay(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, JRConfig.SPEC);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            com.vyrriox.justreplay.client.JustReplayClient.registerConfigScreen(container);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
