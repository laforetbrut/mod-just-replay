package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.client.hook.HudLayer;
import com.vyrriox.justreplay.compat.ReplayCompat;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The frame, level and HUD hooks in the order of the other loaders. Fabric 1.20.1 has no event for the frame or the HUD
 * as the other loaders have them, so mixins call these methods.
 *
 * @author vyrriox
 */
public final class FabricRenderHooks {
    /** Same step as the other loaders of this version: a layer draws above whatever came before it in depth. */
    private static final float LAYER_DEPTH = 200.0F;

    private static final List<HudLayer> TOP_LAYERS = new ArrayList<>();

    private FabricRenderHooks() {
    }

    public static void onFramePre(float partialTick) {
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

    /** Our HUD layers, drawn after every vanilla part of the HUD, so they stay above all of it. */
    public static void onHud(GuiGraphics graphics, float partialTick) {
        if (TOP_LAYERS.isEmpty()) {
            ClientHooks.registerGuiLayers((name, layer) -> TOP_LAYERS.add(layer));
            TOP_LAYERS.add((layerGraphics, delta) -> OverlayPlacementTool.onRenderGui(layerGraphics));
        }
        graphics.pose().pushPose();
        for (HudLayer layer : TOP_LAYERS) {
            graphics.pose().translate(0.0F, 0.0F, LAYER_DEPTH);
            layer.render(graphics, partialTick);
        }
        graphics.pose().popPose();
    }

    /**
     * The world pass of the translucent stage: the camera and the model view of the level, as the event of the other
     * loaders hands them.
     */
    public static void onTranslucent(Camera camera, PoseStack view, float partialTick) {
        OverlayRenderer.onRenderLevel(true, false, camera, view, partialTick);
        PathRenderer.onRenderLevel(camera, view, partialTick);
        EntityTool.onRenderLevel(camera, view);
        OverlayPlacementTool.onRenderLevel(camera, view);
        PlacementTool.onRenderLevel(camera, view);
    }

    /** The world pass after particles, which Fabric 1.20.1 reports at the end of the level. */
    public static void onParticles(Camera camera, PoseStack view, float partialTick) {
        OverlayRenderer.onRenderLevel(false, true, camera, view, partialTick);
    }
}
