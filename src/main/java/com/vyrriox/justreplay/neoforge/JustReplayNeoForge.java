package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * NeoForge entry point.
 *
 * @author vyrriox
 */
@Mod(JustReplay.MOD_ID)
public final class JustReplayNeoForge {
    public JustReplayNeoForge() {
        if (FMLEnvironment.dist.isClient()) {
            NeoForgeClient.init(FMLJavaModLoadingContext.get());
        }
    }
}
