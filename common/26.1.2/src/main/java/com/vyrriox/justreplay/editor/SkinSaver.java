package com.vyrriox.justreplay.editor;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

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
     * Saves the skin shown by this entity. Render thread only; the pixels arrive when the GPU has copied them, the
     * file is written on the IO pool and the callback runs back on the render thread with the new file name, or null
     * on failure.
     */
    public static void saveCurrent(Entity entity, String label, Consumer<String> done) {
        if (!(entity instanceof AbstractClientPlayer player)) {
            done.accept(null);
            return;
        }
        try {
            if (download(player.getSkin().body().texturePath(), pixels -> {
                if (pixels == null) {
                    JustReplay.LOGGER.warn("Cannot read the skin of {}", label);
                    done.accept(null);
                } else {
                    write(pixels, label, done);
                }
            })) {
                return;
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot read the skin of {}: {}", label, t.toString());
        }
        done.accept(null);
    }

    private static void write(NativeImage pixels, String label, Consumer<String> done) {
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

    /**
     * Reads a loaded texture back from the GPU. The result gets the pixels on the render thread once the copy is
     * done, or null when that failed; false, with no result to come, when it is not loaded or has no sensible size.
     */
    private static boolean download(Identifier location, Consumer<NativeImage> result) {
        RenderSystem.assertOnRenderThread();
        AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(location);
        GpuTexture texture = loaded == null ? null : loaded.getTexture();
        if (texture == null) {
            return false;
        }
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            return false;
        }
        int pixelSize = texture.getFormat().pixelSize();
        GpuDevice device = RenderSystem.getDevice();
        GpuBuffer buffer = device.createBuffer(() -> "Skin readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) width * height * pixelSize);
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            NativeImage image = null;
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false)) {
                image = new NativeImage(width, height, false);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        image.setPixelABGR(x, y, read.data().getInt((x + y * width) * pixelSize));
                    }
                }
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Cannot map the skin readback: {}", t.toString());
                if (image != null) {
                    image.close();
                    image = null;
                }
            } finally {
                buffer.close();
            }
            result.accept(image);
        }, 0);
        return true;
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
