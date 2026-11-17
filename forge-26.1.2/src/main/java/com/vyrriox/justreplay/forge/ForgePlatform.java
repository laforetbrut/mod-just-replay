package com.vyrriox.justreplay.forge;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.Platform;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link Platform} on Forge. Created by name.
 *
 * @author vyrriox
 */
public final class ForgePlatform implements Platform {
    @Override
    public String loaderName() {
        return "forge";
    }

    @Override
    public String modVersion() {
        return ModList.getModContainerById(JustReplay.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("");
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String id) {
        return ModList.getLoadedMods() != null && ModList.isLoaded(id);
    }

    @Override
    public List<String> modList() {
        return ModList.getMods().stream().map(info -> info.getModId() + "@" + info.getVersion()).toList();
    }
}
