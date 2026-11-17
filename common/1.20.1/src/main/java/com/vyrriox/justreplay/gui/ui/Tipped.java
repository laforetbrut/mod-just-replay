package com.vyrriox.justreplay.gui.ui;

import net.minecraft.network.chat.Component;

/**
 * A widget with a glass tooltip, shown by {@link GlassScreen} after a short hover.
 *
 * @author vyrriox
 */
public interface Tipped {
    /** Tooltip text, or null for none. */
    Component tooltip();
}
