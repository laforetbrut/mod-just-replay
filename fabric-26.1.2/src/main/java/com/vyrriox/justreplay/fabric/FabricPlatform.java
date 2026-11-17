package com.vyrriox.justreplay.fabric;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.Platform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link Platform} on Fabric. Created by name.
 *
 * @author vyrriox
 */
public final class FabricPlatform implements Platform {
    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(JustReplay.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    @Override
    public List<String> modList() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString()).toList();
    }
}
