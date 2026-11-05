package com.vyrriox.justreplay.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * What the mod needs from the loader on both physical sides.
 *
 * @author vyrriox
 */
public interface Platform {
    static Platform get() {
        return Holder.INSTANCE;
    }

    /** {@code neoforge}, {@code forge} or {@code fabric}. */
    String loaderName();

    /** Version of this mod, or an empty string when unknown. */
    String modVersion();

    Path configDir();

    Path gameDir();

    /** False while the loader has no mod list yet. */
    boolean isModLoaded(String id);

    /** Every loaded mod as {@code id@version}. */
    List<String> modList();

    /** Initialized on first use. */
    final class Holder {
        private static final Platform INSTANCE = LoaderGlue.create(Platform.class, "Platform");

        private Holder() {
        }
    }
}
