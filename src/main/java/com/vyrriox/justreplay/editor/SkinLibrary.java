package com.vyrriox.justreplay.editor;

import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

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
 * Player skins of the {@code justreplay/skins} folder of the game directory, for player actors that wear a skin
 * file instead of the skin of a Mojang account. Each PNG (64x64, or legacy 64x32 converted like vanilla does) is
 * decoded once, uploaded as a dynamic texture and kept until the replay ends. A missing or broken file logs one
 * warning and the actor keeps the default skin.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class SkinLibrary {
    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static int generation;

    private SkinLibrary() {
    }

    /** The skins folder, created on first use. */
    public static Path folder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(JustReplay.MOD_ID).resolve("skins");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Cannot prepare the skins folder {}: {}", dir, e.toString());
        }
        return dir;
    }

    /** File names of the PNG skins in the folder, sorted. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Path dir = folder();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(".png") && Files.isRegularFile(p)) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            JustReplay.LOGGER.warn("Cannot list the skins folder {}: {}", dir, e.toString());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** The texture of a skin file, loading it on first use; null when it is missing or broken. Render thread only. */
    public static ResourceLocation get(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        ResourceLocation loaded = LOADED.get(fileName);
        if (loaded != null || FAILED.contains(fileName)) {
            return loaded;
        }
        try {
            loaded = load(fileName);
            LOADED.put(fileName, loaded);
        } catch (Throwable t) {
            FAILED.add(fileName);
            JustReplay.LOGGER.warn("Cannot load actor skin {}, the default skin is used instead: {}", fileName, t.toString());
        }
        return loaded;
    }

    /** Forgets one file so its next use reads it again, after it was edited. */
    public static void reload(String fileName) {
        if (fileName == null) {
            return;
        }
        FAILED.remove(fileName);
        ResourceLocation loaded = LOADED.remove(fileName);
        if (loaded != null) {
            Minecraft.getInstance().getTextureManager().release(loaded);
        }
    }

    public static boolean isEmpty() {
        return LOADED.isEmpty() && FAILED.isEmpty();
    }

    /** Releases every texture and forgets every skin; called when the replay ends. */
    public static void releaseAll() {
        for (ResourceLocation loaded : LOADED.values()) {
            Minecraft.getInstance().getTextureManager().release(loaded);
        }
        LOADED.clear();
        FAILED.clear();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (ReplaySession.current() == null && (!isEmpty() || ActorManager.hasCustomSkins())) {
            releaseAll();
            ActorManager.clearCustomSkins();
        }
    }

    private static ResourceLocation load(String fileName) throws Exception {
        Path dir = folder().normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new FileNotFoundException(file.toString());
        }
        NativeImage image = NativeImage.read(Files.readAllBytes(file));
        image = processLegacySkin(image);
        ResourceLocation location = JustReplay.id("actor_skins/" + sanitize(fileName));
        if (LOADED.containsValue(location)) {
            // Two file names that sanitize alike must not share one texture.
            location = JustReplay.id("actor_skins/" + sanitize(fileName) + "_" + (++generation));
        }
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        return location;
    }

    /** Same conversion as the vanilla skin download: legacy 64x32 skins get their left limbs, opaque base layers. */
    private static NativeImage processLegacySkin(NativeImage image) throws IOException {
        int height = image.getHeight();
        int width = image.getWidth();
        if (width != 64 || (height != 32 && height != 64)) {
            image.close();
            throw new IOException("a skin must be 64x64 or 64x32 pixels, this one is " + width + "x" + height);
        }
        boolean legacy = height == 32;
        if (legacy) {
            NativeImage full = new NativeImage(64, 64, true);
            full.copyFrom(image);
            image.close();
            image = full;
            full.fillRect(0, 32, 64, 32, 0);
            full.copyRect(4, 16, 16, 32, 4, 4, true, false);
            full.copyRect(8, 16, 16, 32, 4, 4, true, false);
            full.copyRect(0, 20, 24, 32, 4, 12, true, false);
            full.copyRect(4, 20, 16, 32, 4, 12, true, false);
            full.copyRect(8, 20, 8, 32, 4, 12, true, false);
            full.copyRect(12, 20, 16, 32, 4, 12, true, false);
            full.copyRect(44, 16, -8, 32, 4, 4, true, false);
            full.copyRect(48, 16, -8, 32, 4, 4, true, false);
            full.copyRect(40, 20, 0, 32, 4, 12, true, false);
            full.copyRect(44, 20, -8, 32, 4, 12, true, false);
            full.copyRect(48, 20, -16, 32, 4, 12, true, false);
            full.copyRect(52, 20, -8, 32, 4, 12, true, false);
        }
        setNoAlpha(image, 0, 0, 32, 16);
        if (legacy) {
            doNotchTransparencyHack(image, 32, 0, 64, 32);
        }
        setNoAlpha(image, 0, 16, 64, 32);
        setNoAlpha(image, 16, 48, 48, 64);
        return image;
    }

    /** A legacy hat layer without any transparent pixel was meant to be invisible. */
    private static void doNotchTransparencyHack(NativeImage image, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if ((image.getPixelRGBA(x, y) >> 24 & 0xFF) < 128) {
                    return;
                }
            }
        }
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) & 0xFFFFFF);
            }
        }
    }

    private static void setNoAlpha(NativeImage image, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xFF000000);
            }
        }
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return out.toString();
    }
}
