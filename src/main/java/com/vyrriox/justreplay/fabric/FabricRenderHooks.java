package com.vyrriox.justreplay.fabric;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.compat.ReplayCompat;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;

/**
 * The frame hooks in the order of the other loaders, called from a mixin since Fabric has no such event.
 *
 * @author vyrriox
 */
public final class FabricRenderHooks {
    private FabricRenderHooks() {
    }

    public static void onFramePre(float partialTick) {
        // Before anything renders (mods read their clock while rendering): integration clocks move to this frame.
        ReplayCompat.Events.onRenderFrameFirst(partialTick);
        ClientHooks.onFramePre(partialTick);
        ReplayCompat.Events.onRenderFrame(partialTick);
        OverlayRenderer.onRenderFrame();
        EntityTool.onRenderFrame();
        OverlayPlacementTool.onRenderFrame();
        PlacementTool.onRenderFrame();
    }

    public static void onFramePost(float partialTick) {
        ClientHooks.onFramePost(partialTick);
        FxEngine.onFrameEnd();
    }
}
