package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge entry point.
 *
 * @author vyrriox
 */
@Mod(JustReplay.MOD_ID)
public final class JustReplayNeoForge {
    public JustReplayNeoForge(IEventBus modEventBus, ModContainer container) {
        if (FMLEnvironment.dist.isClient()) {
            NeoForgeClient.init(modEventBus, container);
        }
    }
}
