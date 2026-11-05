package com.vyrriox.justreplay.platform;

import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Tells whether a mod is present while mixins are being selected, before mods are constructed. The loader classes are
 * reached by name, so this class compiles on every loader.
 *
 * @author vyrriox
 */
public final class EarlyMods {
    private EarlyMods() {
    }

    public static boolean isLoaded(String modId) {
        ClassLoader loader = EarlyMods.class.getClassLoader();
        try {
            Class<?> fabric = find("net.fabricmc.loader.api.FabricLoader", loader);
            if (fabric != null) {
                Object instance = fabric.getMethod("getInstance").invoke(null);
                return (boolean) fabric.getMethod("isModLoaded", String.class).invoke(instance, modId);
            }
            // Split so that a search of the common folder for loader packages only finds real dependencies.
            Class<?> neoForge = find("net.neo" + "forged.fml" + ".loading.FMLLoader", loader);
            if (neoForge != null) {
                return hasModFile(neoForge.getMethod("getLoadingModList").invoke(null), modId);
            }
            Class<?> forge = find("net.minecraftforge.fml" + ".loading.LoadingModList", loader);
            if (forge != null) {
                return hasModFile(forge.getMethod("get").invoke(null), modId);
            }
        } catch (ReflectiveOperationException e) {
            LoggerFactory.getLogger(EarlyMods.class).error("Cannot read the early mod list", e);
        }
        return false;
    }

    /** {@code neoforge}, {@code forge} or {@code fabric}, read from the loader classes present. */
    public static String loaderName() {
        ClassLoader loader = EarlyMods.class.getClassLoader();
        if (find("net.fabricmc.loader.api.FabricLoader", loader) != null) {
            return "fabric";
        }
        return find("net.neo" + "forged.fml" + ".loading.FMLLoader", loader) != null ? "neoforge" : "forge";
    }

    private static boolean hasModFile(Object list, String modId) throws ReflectiveOperationException {
        if (list == null) {
            return false;
        }
        Method byId = list.getClass().getMethod("getModFileById", String.class);
        return byId.invoke(list, modId) != null;
    }

    private static Class<?> find(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
