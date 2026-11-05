package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Distant Horizons in recordings and replays.
 * <p>
 * <b>Recording.</b> DH keeps one SQLite database ({@code DistantHorizons.sqlite}) per level: inside the world save
 * (the dimension's {@code data} folder) in singleplayer, under {@code Distant_Horizons_server_data/<server>/<dimension>}
 * on a server. For each dimension the player visits, the folder DH reports for that level
 * ({@code IDhApiLevelWrapper.getDhSaveFolder()}) is written to the replay's meta, relative to the game folder.
 * <p>
 * <b>Replay.</b> The replay plays in a throw-away singleplayer world rebuilt from packets. When the replay opens, the
 * databases of the recorded dimensions are copied once into a temporary folder ({@code justreplay/dh-session}); a DH
 * save structure override ({@code IDhApiSaveStructure}) points the replay world's levels at those copies, and only
 * for folders inside the replay world. DH never opens the original database. The DH world is set read only as soon as
 * its first level loads, so the void chunks of the replay host and the replayed chunks never overwrite the recorded
 * LODs, and distant generation is turned off with an API override (DH saves it in its config file: the player's value
 * is noted first and put back even after a crash). Without a copy (option off, older recording, missing or too large
 * database) the replay world is empty and read only, so no LOD shows; rendering itself is never changed. The temporary
 * copies are deleted once the replay world has closed.
 * <p>
 * Everything is reached by reflection through DH's public API interfaces: nothing here loads a DH class when DH is
 * missing.
 *
 * @author vyrriox
 */
final class DistantHorizonsCompat {
    private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";
    private static final String DH_API = "com.seibel.distanthorizons.api.DhApi";
    private static final String API = "com.seibel.distanthorizons.api.interfaces.";
    private static final String INJECTOR = "com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector";
    private static final String GENERATION = "config.both.IDhApiWorldGenerationConfig";
    private static final String SAVE_STRUCTURE = "override.levelHandling.IDhApiSaveStructure";
    /** Name of DH's level database; its journal files share the prefix. */
    private static final String DATABASE = "DistantHorizons.sqlite";
    /** Above other mods' default save structure overrides (10), only while a replay with copied LODs runs. */
    private static final int OVERRIDE_PRIORITY = 100;

    /** Dimension id to its copied DH folder, for the replay being opened or played. */
    private static final Map<String, Path> STAGED = new ConcurrentHashMap<>();
    private static volatile Path sessionWorld;
    private static volatile boolean redirecting;
    private static Object saveStructure;

    private static boolean overridden;
    private static boolean recoveryChecked;
    private static boolean readOnlyApplied;
    private static int retry;
    private static int describeRetry;

    private DistantHorizonsCompat() {
    }

    private static Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName(API + name, false, DistantHorizonsCompat.class.getClassLoader());
    }

    private static Object delayed(String field) throws Exception {
        return Class.forName(DELAYED, true, DistantHorizonsCompat.class.getClassLoader()).getField(field).get(null);
    }

    private static Path gameDir() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    }

    private static Path stagingRoot() {
        return gameDir().resolve(JustReplay.MOD_ID).resolve("dh-session");
    }

    // ---------------------------------------------------------------- recording

    /**
     * Writes into the meta the DH folder of the level's dimension when it is not there yet. Called every client tick
     * of a recording ({@code force} false: one lookup per second at most until found) and once when a meta is created.
     */
    static void describe(ReplayMeta meta, Level level, boolean force) {
        if (meta == null || level == null) {
            return;
        }
        String dimension = level.dimension().location().toString();
        if (meta.distantHorizons.containsKey(dimension)) {
            return;
        }
        if (!force && describeRetry-- > 0) {
            return;
        }
        describeRetry = 20;
        try {
            String folder = levelFolder(dimension);
            if (folder != null) {
                meta.distantHorizons.put(dimension, folder);
                JustReplay.LOGGER.info("Distant Horizons: LOD folder of {} noted for the recording: {}", dimension, folder);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.describe", "Cannot read the Distant Horizons level folders", t);
        }
    }

    /** The loaded DH level folder of a dimension holding a database, relative to the game folder, or null. */
    private static String levelFolder(String dimension) throws Exception {
        Object world = delayed("worldProxy");
        Class<?> proxy = api("world.IDhApiWorldProxy");
        if (world == null || !(boolean) proxy.getMethod("worldLoaded").invoke(world)) {
            return null;
        }
        Class<?> levelType = api("world.IDhApiLevelWrapper");
        Method name = levelType.getMethod("getDimensionName");
        Method folder = levelType.getMethod("getDhSaveFolder");
        Iterable<?> levels = (Iterable<?>) proxy.getMethod("getAllLoadedLevelWrappers").invoke(world);
        Path game = gameDir();
        for (Object level : levels) {
            if (level == null || !dimension.equals(name.invoke(level))) {
                continue;
            }
            File dir = (File) folder.invoke(level);
            if (dir == null) {
                continue;
            }
            Path path = dir.toPath().toAbsolutePath().normalize();
            if (!Files.isRegularFile(path.resolve(DATABASE))) {
                continue;
            }
            if (!path.startsWith(game)) {
                ReplayCompat.warnOnce("dh.outside", "Distant Horizons saves outside the game folder, LODs are not linked to recordings", null);
                return null;
            }
            return game.relativize(path).toString().replace('\\', '/');
        }
        return null;
    }

    // ---------------------------------------------------------------- replay preparation

    /**
     * Copies the recorded databases for the replay about to open. Runs off the render thread, before the replay world
     * is created; at most one copy per recorded dimension.
     */
    static void prepare(ReplayMeta meta) {
        redirecting = false;
        STAGED.clear();
        deleteStaging();
        if (!JRConfig.DISTANT_HORIZONS_LODS.get()) {
            JustReplay.LOGGER.info("Distant Horizons: LODs in replays are turned off in the config");
            return;
        }
        Map<String, String> folders = meta == null ? null : meta.distantHorizons;
        if (folders == null || folders.isEmpty()) {
            JustReplay.LOGGER.info("Distant Horizons: this recording has no LOD folder (made without Distant Horizons or before"
                    + " this feature), the replay plays without LODs");
            return;
        }
        Path game = gameDir();
        Path root = stagingRoot();
        long limit = JRConfig.DISTANT_HORIZONS_MAX_COPY_MB.get() * 1024L * 1024L;
        long copied = 0;
        long start = System.nanoTime();
        int index = 0;
        for (Map.Entry<String, String> entry : folders.entrySet()) {
            String dimension = entry.getKey();
            Path source = game.resolve(entry.getValue()).toAbsolutePath().normalize();
            index++;
            if (!source.startsWith(game) || !Files.isRegularFile(source.resolve(DATABASE))) {
                JustReplay.LOGGER.info("Distant Horizons: no LOD database for {} at {}, that dimension plays without LODs",
                        dimension, entry.getValue());
                continue;
            }
            try {
                List<Path> files = databaseFiles(source);
                long size = 0;
                for (Path file : files) {
                    size += Files.size(file);
                }
                if (size > limit) {
                    JustReplay.LOGGER.info("Distant Horizons: LOD database of {} is {} MB, above distantHorizonsMaxCopyMb, that"
                            + " dimension plays without LODs", dimension, size / (1024 * 1024));
                    continue;
                }
                Path target = root.resolve(index + "_" + dimension.replaceAll("[^A-Za-z0-9._-]", "_"));
                Files.createDirectories(target);
                for (Path file : files) {
                    Files.copy(file, target.resolve(file.getFileName().toString()));
                }
                STAGED.put(dimension, target);
                copied += size;
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Distant Horizons: cannot copy the LOD database of {}: {}", dimension, t.toString());
            }
        }
        if (STAGED.isEmpty()) {
            return;
        }
        sessionWorld = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(ReplaySession.WORLD_NAME)
                .toAbsolutePath().normalize();
        try {
            bindSaveStructure();
            redirecting = true;
            JustReplay.LOGGER.info("Distant Horizons: LODs of {} dimension(s) copied for the replay ({} MB in {} ms)",
                    STAGED.size(), copied / (1024 * 1024), (System.nanoTime() - start) / 1_000_000L);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.bind", "Cannot redirect Distant Horizons to the recorded LODs", t);
            STAGED.clear();
        }
    }

    /** The database and its journal files (same name prefix) of a DH level folder. */
    private static List<Path> databaseFiles(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(folder)) {
            list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(DATABASE))
                    .forEach(files::add);
        }
        return files;
    }

    private static void bindSaveStructure() throws Exception {
        if (saveStructure != null) {
            return;
        }
        ClassLoader loader = DistantHorizonsCompat.class.getClassLoader();
        Class<?> type = api(SAVE_STRUCTURE);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "overrideFilePath" -> redirect((File) args[0], args[1]);
            case "getPriority" -> OVERRIDE_PRIORITY;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Just Replay recorded LOD folders";
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
        };
        Object override = Proxy.newProxyInstance(loader, new Class<?>[]{type}, handler);
        injector("bind").invoke(Class.forName(DH_API, true, loader).getField("overrides").get(null), type, override);
        saveStructure = override;
    }

    /** {@code bind} or {@code unbind} of DH's public override injector interface (two arguments: interface, override). */
    private static Method injector(String name) throws Exception {
        Class<?> type = Class.forName(INJECTOR, false, DistantHorizonsCompat.class.getClassLoader());
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 2) {
                return method;
            }
        }
        throw new NoSuchMethodException(INJECTOR + "." + name);
    }

    private static void unbindSaveStructure() {
        Object override = saveStructure;
        saveStructure = null;
        if (override == null) {
            return;
        }
        try {
            ClassLoader loader = DistantHorizonsCompat.class.getClassLoader();
            injector("unbind").invoke(Class.forName(DH_API, true, loader).getField("overrides").get(null),
                    api(SAVE_STRUCTURE), override);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.unbind", "Cannot remove the Distant Horizons folder override", t);
        }
    }

    /**
     * DH asks which folder a level it loads uses. Only levels of the replay world are redirected, to the copy of the
     * recorded dimension; anything else keeps DH's own folder (null).
     */
    private static File redirect(File current, Object level) {
        Path session = sessionWorld;
        if (!redirecting || current == null || session == null
                || !current.toPath().toAbsolutePath().normalize().startsWith(session)) {
            return null;
        }
        // Before DH opens this level: nothing it receives from the replay may be turned into LODs.
        applyReadOnly();
        String dimension;
        try {
            dimension = (String) api("world.IDhApiLevelWrapper").getMethod("getDimensionName").invoke(level);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.level", "Cannot read the Distant Horizons level name", t);
            return null;
        }
        Path staged = dimension == null ? null : STAGED.get(dimension);
        if (staged == null) {
            return null;
        }
        JustReplay.LOGGER.info("Distant Horizons: replay level {} reads the recorded LODs", dimension);
        return staged.toFile();
    }

    private static synchronized void applyReadOnly() {
        if (readOnlyApplied) {
            return;
        }
        try {
            Object world = delayed("worldProxy");
            Class<?> proxy = api("world.IDhApiWorldProxy");
            if (world != null && (boolean) proxy.getMethod("worldLoaded").invoke(world)) {
                proxy.getMethod("setReadOnly", boolean.class).invoke(world, true);
                readOnlyApplied = true;
                JustReplay.LOGGER.info("Distant Horizons: replay world set read only, no LOD is built or saved");
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.readonly", "Cannot set the Distant Horizons world read only", t);
        }
    }

    // ---------------------------------------------------------------- replay lifetime

    /**
     * A DH config value, {@code configs.<group>().<value>()}, or null while DH is not ready. Methods come from the
     * public API interfaces, never from DH's implementation classes.
     */
    private static Object configValue(String group, String groupType, String value) throws Exception {
        Object configs = delayed("configs");
        if (configs == null) {
            return null;
        }
        Object groupObject = api("config.IDhApiConfig").getMethod(group).invoke(configs);
        return groupObject == null ? null : api(groupType).getMethod(value).invoke(groupObject);
    }

    private static boolean override(String group, String groupType, String value, Object setting) throws Exception {
        Object config = configValue(group, groupType, value);
        if (config == null) {
            return false;
        }
        return (boolean) api("config.IDhApiConfigValue").getMethod("setValue", Object.class).invoke(config, setting);
    }

    private static void clear(String group, String groupType, String value) throws Exception {
        Object config = configValue(group, groupType, value);
        if (config != null) {
            api("config.IDhApiConfigValue").getMethod("clearValue").invoke(config);
        }
    }

    /**
     * DH writes a value set through its API into its config file. A replay that ends normally gives the player's
     * value back; one that ends with a crash would leave distant generation off for good. So the player's value is
     * noted in this file before it is changed, and put back at the next start when the file is still there.
     * Rendering is never touched: without recorded LODs the replay world is empty and read only, nothing shows.
     */
    private static Path restoreFile() {
        return gameDir().resolve("justreplay").resolve("dh-restore.properties");
    }

    private static Object currentValue(String group, String groupType, String value) throws Exception {
        Object config = configValue(group, groupType, value);
        return config == null ? null : api("config.IDhApiConfigValue").getMethod("getValue").invoke(config);
    }

    /** Puts the player's own value back: set it (DH saves it) and drop the API layer. */
    private static void restore(boolean generation) throws Exception {
        override("worldGenerator", GENERATION, "enableDistantWorldGeneration", generation);
        clear("worldGenerator", GENERATION, "enableDistantWorldGeneration");
    }

    private static Boolean savedGeneration() {
        Path file = restoreFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            java.util.Properties p = new java.util.Properties();
            try (java.io.Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            }
            String v = p.getProperty("enableDistantWorldGeneration");
            return v == null ? null : Boolean.valueOf(v);
        } catch (IOException e) {
            return null;
        }
    }

    /** Outside a replay: a note left by a replay that never ended puts the player's DH setting back, once. */
    static void recoverAfterInterruptedReplay() {
        if (recoveryChecked) {
            return;
        }
        try {
            if (delayed("configs") == null) {
                return;
            }
            recoveryChecked = true;
            Boolean generation = savedGeneration();
            if (generation != null) {
                restore(generation);
                JustReplay.LOGGER.info("Distant Horizons: distant generation set back to {} after a replay that did not end normally",
                        generation);
            }
            Files.deleteIfExists(restoreFile());
        } catch (Throwable t) {
            recoveryChecked = true;
            ReplayCompat.warnOnce("dh.recover", "Cannot restore the Distant Horizons settings after an interrupted replay", t);
        }
    }

    static void onReplayStart() {
        overridden = false;
        retry = 0;
        if (!redirecting || STAGED.isEmpty()) {
            JustReplay.LOGGER.info("Distant Horizons: no recorded LODs for this replay, the distant terrain stays empty");
        }
        tick();
    }

    /** Turns distant generation off as soon as DH is ready, and the read only mode once DH has loaded the replay world. */
    static void tick() {
        if (overridden && readOnlyApplied) {
            return;
        }
        if (retry > 0) {
            retry--;
            return;
        }
        try {
            if (!overridden) {
                Object current = currentValue("worldGenerator", GENERATION, "enableDistantWorldGeneration");
                if (current instanceof Boolean value && !Files.exists(restoreFile())) {
                    Files.createDirectories(restoreFile().getParent());
                    Files.writeString(restoreFile(), "# Distant Horizons setting of the player, put back if a replay ends with a crash\n"
                            + "enableDistantWorldGeneration=" + value + "\n");
                }
                overridden = current != null && override("worldGenerator", GENERATION, "enableDistantWorldGeneration", Boolean.FALSE);
                if (overridden) {
                    JustReplay.LOGGER.info("Distant Horizons: distant generation turned off for the replay");
                }
            }
            applyReadOnly();
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.apply", "Cannot configure Distant Horizons for the replay", t);
        }
        retry = 20;
    }

    static void onReplayEnd() {
        try {
            if (overridden) {
                Boolean generation = savedGeneration();
                if (generation != null) {
                    restore(generation);
                } else {
                    clear("worldGenerator", GENERATION, "enableDistantWorldGeneration");
                }
                JustReplay.LOGGER.info("Distant Horizons: settings restored after the replay");
            }
            Files.deleteIfExists(restoreFile());
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dh.clear", "Cannot restore the Distant Horizons settings", t);
        }
        overridden = false;
        readOnlyApplied = false;
    }

    /**
     * The replay world has closed (DH has released its databases): the folder override goes and the copies are
     * deleted.
     */
    static void onReplayWorldClosed() {
        redirecting = false;
        unbindSaveStructure();
        if (STAGED.isEmpty() && !Files.isDirectory(stagingRoot())) {
            return;
        }
        STAGED.clear();
        deleteStaging();
    }

    private static void deleteStaging() {
        Path root = stagingRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            // Plain files and folders only: walk does not follow links.
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    ReplayCompat.warnOnce("dh.delete", "Cannot delete the temporary Distant Horizons copy " + p, e);
                }
            });
            JustReplay.LOGGER.info("Distant Horizons: temporary LOD copies deleted");
        } catch (IOException e) {
            ReplayCompat.warnOnce("dh.delete", "Cannot delete the temporary Distant Horizons copies", e);
        }
    }
}
