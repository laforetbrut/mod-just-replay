package com.vyrriox.justreplay.editor.fx;

import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Colour lookup tables of {@code <game dir>/justreplay/luts/}: PNG images holding an N x N x N cube as N slices
 * of N x N pixels, laid out in a strip (1024 x 32 for N = 32) or a grid (512 x 512 holds 8 x 8 slices of 64).
 * Red grows to the right inside a slice, green downwards, blue from slice to slice. Loaded on the render thread
 * the first time a filter uses them and kept as linear filtered textures.
 *
 * @author vyrriox
 */
public final class LutLibrary {
    /** A loaded table: its texture and the layout the shader needs. */
    public record Lut(DynamicTexture texture, int size, int width, int height, long stamp) {
    }

    private static final Map<String, Lut> CACHE = new HashMap<>();
    private static final Map<String, Long> FAILED = new HashMap<>();

    private LutLibrary() {
    }

    public static Path folder() {
        Path dir = FMLPaths.GAMEDIR.get().resolve(JustReplay.MOD_ID).resolve("luts");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot create folder {}", dir, e);
        }
        return dir;
    }

    /** PNG files of the folder, sorted by name. */
    public static List<String> list() {
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder())) {
            stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")).sorted().forEach(files::add);
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Cannot list the LUT folder: {}", e.toString());
        }
        return files;
    }

    /** Cube size of an image layout, or 0 when the size is not a LUT layout. */
    static int cubeSize(int w, int h) {
        if (w == h * h && h >= 2) {
            return h; // strip
        }
        if (w == h) {
            int n = (int) Math.round(Math.cbrt((double) w * h));
            if (n >= 2 && (long) n * n * n == (long) w * h && w % n == 0) {
                return n; // grid
            }
        }
        return 0;
    }

    /**
     * The table of a file, loading it the first time; null when the file is missing or not a LUT. Render thread
     * only. A file that failed is not tried again until it changes on disk.
     */
    public static Lut get(String file) {
        if (file == null || file.isEmpty() || file.contains("/") || file.contains("\\") || file.contains("..")) {
            return null;
        }
        Lut cached = CACHE.get(file);
        if (cached != null) {
            return cached;
        }
        Path path = folder().resolve(file);
        long stamp;
        try {
            stamp = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return null;
        }
        Long failed = FAILED.get(file);
        if (failed != null && failed == stamp) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(NativeImage.Format.RGBA, in);
            int size = cubeSize(image.getWidth(), image.getHeight());
            if (size == 0) {
                JustReplay.LOGGER.warn("{} is {}x{}, not a LUT layout (strip N*N x N or grid like 512x512)", file, image.getWidth(),
                        image.getHeight());
                image.close();
                FAILED.put(file, stamp);
                return null;
            }
            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(true, false);
            Lut lut = new Lut(texture, size, image.getWidth(), image.getHeight(), stamp);
            CACHE.put(file, lut);
            JustReplay.LOGGER.info("Loaded LUT {} ({}x{}, cube {})", file, image.getWidth(), image.getHeight(), size);
            return lut;
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot read LUT {}: {}", file, e.toString());
            FAILED.put(file, stamp);
            return null;
        }
    }

    /** Drops every loaded table so changed files are read again. Render thread only. */
    public static void reload() {
        for (Lut lut : CACHE.values()) {
            lut.texture().close();
        }
        CACHE.clear();
        FAILED.clear();
    }
}
