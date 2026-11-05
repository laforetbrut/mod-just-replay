package com.vyrriox.justreplay.replay;

import net.minecraft.client.Minecraft;

/**
 * Nothing to repair on this version: the level renderer has no separate occlusion graph that another view could leave
 * on a released section grid.
 *
 * @author vyrriox
 */
public final class SectionRepair {
    private SectionRepair() {
    }

    public static void beforeFrame(Minecraft mc) {
    }
}
