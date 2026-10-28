package com.vyrriox.justreplay.editor;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Writes the skin a player currently shows into the skins folder as a new PNG, so it can be painted in an image
 * editor and worn again through a skin change. The pixels are read back from the loaded texture, which covers skin
 * files, downloaded Mojang skins and default skins alike.
 *
 * @author vyrriox
 */
public final class SkinSaver {
    private SkinSaver() {
    }

    /**
     * Saves the skin shown by this entity. Render thread only; the file is written on the IO pool and the callback
     * runs back on the render thread with the new file name, or null on failure.
     */
    public static void saveCurrent(Entity entity, String label, Consumer<String> done) {
        if (!(entity instanceof AbstractClientPlayer player)) {
            done.accept(null);
            return;
        }
        NativeImage image;
        try {
            image = download(player.getSkin().texture());
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot read the skin of {}: {}", label, t.toString());
            image = null;
        }
        if (image == null) {
            done.accept(null);
            return;
        }
        NativeImage pixels = image;
        Minecraft mc = Minecraft.getInstance();
        Util.ioPool().execute(() -> {
            String written = null;
            try {
                Path file = freeFile(SkinLibrary.folder(), label);
                pixels.writeToFile(file);
                written = file.getFileName().toString();
                JustReplay.LOGGER.info("Saved the current skin of {} to {}", label, file);
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Cannot save the skin of {}: {}", label, t.toString());
            } finally {
                pixels.close();
            }
            String result = written;
            mc.execute(() -> done.accept(result));
        });
    }

    /** Reads a loaded texture back from the GPU; null when it is not loaded or has no sensible size. */
    private static NativeImage download(ResourceLocation location) {
        RenderSystem.assertOnRenderThread();
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (texture == null) {
            return null;
        }
        RenderSystem.bindTexture(texture.getId());
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            return null;
        }
        NativeImage image = new NativeImage(width, height, false);
        image.downloadTexture(0, false);
        return image;
    }

    /** A file name from the label and a number that does not exist yet. */
    static Path freeFile(Path dir, String label) {
        String base = label == null ? "" : label.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "skin";
        }
        base = base.toLowerCase(Locale.ROOT);
        for (int n = 1; ; n++) {
            Path file = dir.resolve(base + "_" + n + ".png");
            if (!Files.exists(file)) {
                return file;
            }
        }
    }
}
