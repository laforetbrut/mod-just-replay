package com.vyrriox.justreplay.editor.overlay;

import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Images of the {@code justreplay/images} folder of the game directory, for image overlays. Each file is decoded
 * once (PNG natively, JPG and the first frame of a GIF through ImageIO), uploaded as a dynamic texture and kept
 * until the replay ends. A file that cannot be read logs one warning and is not retried until it is reloaded.
 *
 * @author vyrriox
 */
public final class ImageLibrary {
    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif"};
    /** Larger images are refused rather than eating the video memory. */
    private static final int MAX_SIDE = 8192;

    /** An image ready to draw: its texture and its size in pixels. */
    public record Loaded(ResourceLocation texture, int width, int height) {
        public float aspect() {
            return height <= 0 ? 1.0F : (float) height / Math.max(1, width);
        }
    }

    private static final Map<String, Loaded> LOADED = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static int generation;

    private ImageLibrary() {
    }

    /** The images folder, created on first use. */
    public static Path folder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(JustReplay.MOD_ID).resolve("images");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Cannot prepare the images folder {}: {}", dir, e.toString());
        }
        return dir;
    }

    public static boolean supported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** File names of the images in the folder, sorted. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Path dir = folder();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (supported(name) && Files.isRegularFile(p)) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot list the images folder {}: {}", dir, e.toString());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** The image of a file, loading it on first use; null when it is missing or broken. Render thread only. */
    public static Loaded get(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Loaded loaded = LOADED.get(fileName);
        if (loaded != null || FAILED.contains(fileName)) {
            return loaded;
        }
        try {
            loaded = load(fileName);
            LOADED.put(fileName, loaded);
        } catch (Throwable t) {
            FAILED.add(fileName);
            JustReplay.LOGGER.warn("Cannot load overlay image {}: {}", fileName, t.toString());
        }
        return loaded;
    }

    /** True when the file was tried and could not be loaded. */
    public static boolean failed(String fileName) {
        return fileName != null && FAILED.contains(fileName);
    }

    /** Forgets every image so the next use reads the files again. */
    public static void reload() {
        releaseAll();
    }

    public static boolean isEmpty() {
        return LOADED.isEmpty() && FAILED.isEmpty();
    }

    /** Releases every texture and forgets every image; called when the replay ends. */
    public static void releaseAll() {
        for (Loaded loaded : LOADED.values()) {
            Minecraft.getInstance().getTextureManager().release(loaded.texture());
        }
        LOADED.clear();
        FAILED.clear();
        OverlayRenderer.forgetImageTypes();
    }

    private static Loaded load(String fileName) throws Exception {
        Path dir = folder().normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new FileNotFoundException(file.toString());
        }
        byte[] bytes = Files.readAllBytes(file);
        NativeImage image = decode(bytes);
        if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
            int w = image.getWidth(), h = image.getHeight();
            image.close();
            throw new IOException("image too large (" + w + "x" + h + ", at most " + MAX_SIDE + " pixels per side)");
        }
        generation++;
        ResourceLocation location = JustReplay.id("overlay/" + sanitize(fileName) + "_" + generation);
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return new Loaded(location, image.getWidth(), image.getHeight());
    }

    /** PNG through the game decoder; JPG, GIF and PNG variants it refuses through ImageIO. */
    private static NativeImage decode(byte[] bytes) throws IOException {
        boolean png = bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        if (png) {
            try {
                return NativeImage.read(bytes);
            } catch (IOException e) {
                // An unusual PNG flavour: ImageIO may still read it.
            }
        }
        BufferedImage buffered = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
        if (buffered == null) {
            throw new IOException("unsupported image format");
        }
        int w = buffered.getWidth(), h = buffered.getHeight();
        if (w > MAX_SIDE || h > MAX_SIDE) {
            throw new IOException("image too large (" + w + "x" + h + ", at most " + MAX_SIDE + " pixels per side)");
        }
        NativeImage image = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int argb = buffered.getRGB(px, py);
                // NativeImage stores ABGR.
                int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                image.setPixelRGBA(px, py, abgr);
            }
        }
        return image;
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return out.toString();
    }
}
