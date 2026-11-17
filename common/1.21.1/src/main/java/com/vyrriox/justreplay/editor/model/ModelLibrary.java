package com.vyrriox.justreplay.editor.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Blockbench models of the {@code justreplay/models} folder of the game directory, loaded on first use and kept
 * until the replay ends. Textures are uploaded once as dynamic textures and released with the models. A file
 * that cannot be read logs one warning and is never retried until it is reloaded.
 *
 * @author vyrriox
 */
public final class ModelLibrary {
    public static final String EXTENSION = ".bbmodel";
    private static final String EXAMPLE = "example.bbmodel";

    /** A model ready to draw: its geometry and one texture location per Blockbench texture. */
    public record Loaded(BbModel model, ResourceLocation[] textures) {
    }

    private static final Map<String, Loaded> LOADED = new HashMap<>();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
    private static final List<ResourceLocation> UPLOADED = new ArrayList<>();
    private static int generation;

    private ModelLibrary() {
    }

    /** The models folder, created on first use with the bundled example model in it. */
    public static Path folder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(JustReplay.MOD_ID).resolve("models");
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                try (InputStream in = ModelLibrary.class.getResourceAsStream("/assets/" + JustReplay.MOD_ID + "/models/" + EXAMPLE)) {
                    if (in != null) {
                        Files.copy(in, dir.resolve(EXAMPLE));
                    }
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot prepare the models folder {}: {}", dir, e.toString());
        }
        return dir;
    }

    /** File names of the models in the folder, sorted. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Path dir = folder();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(EXTENSION) && Files.isRegularFile(p)) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot list the models folder {}: {}", dir, e.toString());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** The model of a file, loading it on first use; null when it is missing or broken. Render thread only. */
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
            JustReplay.LOGGER.warn("Cannot load Blockbench model {}: {}", fileName, t.toString());
        }
        return loaded;
    }

    /** Animation names of a model, for the editor. */
    public static List<String> animations(String fileName) {
        if (fileName == null || fileName.isBlank() || (!LOADED.containsKey(fileName) && !exists(fileName))) {
            return List.of();
        }
        Loaded loaded = get(fileName);
        return loaded == null ? List.of() : new ArrayList<>(loaded.model().animations.keySet());
    }

    private static boolean exists(String fileName) {
        try {
            return Files.isRegularFile(folder().resolve(fileName));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Forgets a model so the next use reads the file again. */
    public static void reload(String fileName) {
        Loaded loaded = LOADED.remove(fileName);
        FAILED.remove(fileName);
        if (loaded != null) {
            for (ResourceLocation texture : loaded.textures()) {
                if (UPLOADED.remove(texture)) {
                    Minecraft.getInstance().getTextureManager().release(texture);
                }
            }
        }
    }

    public static boolean isEmpty() {
        return LOADED.isEmpty() && FAILED.isEmpty();
    }

    /** Releases every texture and forgets every model; called when the replay ends. */
    public static void releaseAll() {
        for (ResourceLocation texture : UPLOADED) {
            Minecraft.getInstance().getTextureManager().release(texture);
        }
        UPLOADED.clear();
        LOADED.clear();
        FAILED.clear();
    }

    private static Loaded load(String fileName) throws Exception {
        Path dir = folder();
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir.normalize()) || !Files.isRegularFile(file)) {
            throw new java.io.FileNotFoundException(file.toString());
        }
        List<String> warnings = new ArrayList<>();
        BbModel model = BbModel.read(file, warnings::add);
        if (!warnings.isEmpty()) {
            JustReplay.LOGGER.warn("Blockbench model {}: {}", fileName, String.join("; ", warnings));
        }
        generation++;
        ResourceLocation[] textures = new ResourceLocation[model.textures.size()];
        String base = sanitize(fileName) + "_" + generation;
        for (int i = 0; i < textures.length; i++) {
            byte[] png = model.textures.get(i).png;
            textures[i] = MissingTextureAtlasSprite.getLocation();
            if (png == null) {
                continue;
            }
            try {
                NativeImage image = NativeImage.read(png);
                ResourceLocation location = JustReplay.id("bbmodel/" + base + "/" + i);
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
                UPLOADED.add(location);
                textures[i] = location;
            } catch (Exception e) {
                JustReplay.LOGGER.warn("Blockbench model {}: texture {} is not a readable PNG ({})", fileName, i, e.toString());
            }
        }
        return new Loaded(model, textures);
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return out.toString();
    }
}
