package com.vyrriox.justreplay.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;

/**
 * Locks the render size of the game for the duration of an export. The window keeps its real size, but the
 * framebuffer size Minecraft renders at (main render target, post effects, level renderer, projection) can be
 * switched to the export resolution. While locked, window resize events are recorded instead of applied, and
 * the frame shown in the window is scaled to the real window size. Unlocking puts back the real size.
 *
 * @author vyrriox
 */
public final class ExportRenderSize {
    private static boolean locked;
    private static int realWidth;
    private static int realHeight;

    private ExportRenderSize() {
    }

    public static boolean isLocked() {
        return locked;
    }

    /** Freezes the render size at the current window size. */
    public static void lock() {
        if (locked) {
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        realWidth = window.getWidth();
        realHeight = window.getHeight();
        locked = true;
    }

    /** True when the GPU can hold a render target of this size. */
    public static boolean fits(int width, int height) {
        int max = RenderSystem.maxSupportedTextureSize();
        return width > 0 && height > 0 && width <= max && height <= max;
    }

    /**
     * Switches the render size while locked. Returns true when the main render target now has exactly this size;
     * on any failure the real size is put back and false is returned.
     */
    public static boolean apply(int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        if (!locked) {
            return false;
        }
        RenderTarget target = mc.getMainRenderTarget();
        if (window.getWidth() == width && window.getHeight() == height && target.width == width && target.height == height) {
            return true;
        }
        if (!fits(width, height)) {
            return false;
        }
        try {
            window.setWidth(width);
            window.setHeight(height);
            mc.resizeDisplay();
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot render at {}x{}", width, height, t);
            restore(true);
            return false;
        }
        target = mc.getMainRenderTarget();
        if (target.width != width || target.height != height) {
            JustReplay.LOGGER.warn("Render target is {}x{} instead of {}x{}", target.width, target.height, width, height);
            restore(true);
            return false;
        }
        return true;
    }

    /** Ends the lock and renders at the real window size again. */
    public static void unlock() {
        if (!locked) {
            return;
        }
        locked = false;
        restore(false);
    }

    private static void restore(boolean force) {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        RenderTarget target = mc.getMainRenderTarget();
        boolean changed = window.getWidth() != realWidth || window.getHeight() != realHeight
                || target.width != realWidth || target.height != realHeight;
        if (!changed && !force) {
            return;
        }
        window.setWidth(realWidth);
        window.setHeight(realHeight);
        try {
            mc.resizeDisplay();
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot restore the render size to {}x{}", realWidth, realHeight, t);
        }
    }

    /** Called by the window on a framebuffer resize; returns true when the event must be held back. */
    public static boolean onWindowResize(int width, int height) {
        if (!locked) {
            return false;
        }
        if (width > 0 && height > 0) {
            realWidth = width;
            realHeight = height;
        }
        return true;
    }

    /** Size the finished frame is shown at in the window. */
    public static int screenWidth(int renderWidth) {
        return locked ? realWidth : renderWidth;
    }

    public static int screenHeight(int renderHeight) {
        return locked ? realHeight : renderHeight;
    }
}
