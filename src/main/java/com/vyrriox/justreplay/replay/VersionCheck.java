package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.format.ReplayMeta;

/**
 * Decides whether a recording can be opened by the running game: packet and registry ids differ per
 * Minecraft version, and registries.bin and payload encoding per loader.
 *
 * @author vyrriox
 */
public final class VersionCheck {
    public enum Result { OK, WRONG_VERSION, WRONG_LOADER }

    private VersionCheck() {
    }

    /**
     * Pure decision. A recording without a loader (made before 1.1.0) counts as neoforge when it comes
     * from 1.21.1, the only place those were made; an unknown version is accepted.
     */
    public static Result check(String recordedMc, String recordedLoader, String runningMc, String runningLoader) {
        String mc = recordedMc == null ? "" : recordedMc.trim();
        if (!mc.isEmpty() && !mc.equals(runningMc)) {
            return Result.WRONG_VERSION;
        }
        String loader = recordedLoader == null ? "" : recordedLoader.trim();
        if (loader.isEmpty()) {
            loader = "1.21.1".equals(mc) ? "neoforge" : "";
        }
        if (!loader.isEmpty() && !loader.equalsIgnoreCase(runningLoader)) {
            return Result.WRONG_LOADER;
        }
        return Result.OK;
    }

    /** The version a recording was made on: {@code mc}, else {@code minecraftVersion}, else empty. */
    public static String recordedVersion(ReplayMeta meta) {
        if (meta == null) {
            return "";
        }
        if (meta.mc != null && !meta.mc.isBlank()) {
            return meta.mc;
        }
        return meta.minecraftVersion == null ? "" : meta.minecraftVersion;
    }

    public static String runningVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().getName();
    }

    public static Result check(ReplayMeta meta) {
        String loader = meta == null || meta.loader == null ? "" : meta.loader;
        return check(recordedVersion(meta), loader, runningVersion(), com.vyrriox.justreplay.platform.Platform.get().loaderName());
    }

    /** Self test line: a matching, an older empty, a wrong version and a wrong loader case. */
    public static boolean selfTest() {
        return check("1.21.1", "neoforge", "1.21.1", "neoforge") == Result.OK
                && check("1.21.1", "", "1.21.1", "neoforge") == Result.OK
                && check("1.21.1", "", "1.21.1", "fabric") == Result.WRONG_LOADER
                && check("1.20.1", "forge", "1.21.1", "neoforge") == Result.WRONG_VERSION
                && check("1.21.1", "fabric", "1.21.1", "neoforge") == Result.WRONG_LOADER;
    }
}
