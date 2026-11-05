package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.Platform;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link Platform} on NeoForge. Created by name.
 *
 * @author vyrriox
 */
public final class NeoForgePlatform implements Platform {
    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String modVersion() {
        return ModList.get().getModContainerById(JustReplay.MOD_ID)
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
        ModList list = ModList.get();
        return list != null && list.isLoaded(id);
    }

    @Override
    public List<String> modList() {
        return ModList.get().getMods().stream().map(info -> info.getModId() + "@" + info.getVersion()).toList();
    }
}
