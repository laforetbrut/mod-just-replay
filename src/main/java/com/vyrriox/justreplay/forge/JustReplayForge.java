package com.vyrriox.justreplay.forge;

import com.vyrriox.justreplay.JustReplay;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge entry point.
 *
 * @author vyrriox
 */
@Mod(JustReplay.MOD_ID)
public final class JustReplayForge {
    public JustReplayForge(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist.isClient()) {
            ForgeClient.init(context);
        }
    }
}
