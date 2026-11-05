package com.vyrriox.justreplay.export;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Output size of a video export. {@link #WINDOW} keeps the size of the game window.
 *
 * @author vyrriox
 */
public enum ExportResolution implements TranslatableEnum {
    WINDOW(0, 0),
    HD_720(1280, 720),
    FULL_HD_1080(1920, 1080),
    QHD_1440(2560, 1440),
    UHD_2160(3840, 2160);

    private final int width;
    private final int height;

    ExportResolution(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public boolean isWindow() {
        return this == WINDOW;
    }

    /** Width in pixels, 0 for {@link #WINDOW}. */
    public int width() {
        return width;
    }

    /** Height in pixels, 0 for {@link #WINDOW}. */
    public int height() {
        return height;
    }

    @Override
    public Component getTranslatedName() {
        return isWindow() ? Component.translatable("justreplay.export.resolution.window")
                : Component.literal(width + " x " + height);
    }
}
