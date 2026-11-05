package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.compat.ReplayCompat;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.IOException;

/**
 * NeoForge events of the editor, the replay tools and the integrations, forwarded to their loader-free classes.
 *
 * @author vyrriox
 */
public final class NeoForgeEditorEvents {
    private NeoForgeEditorEvents() {
    }

    public static final class GameEvents {
        private GameEvents() {
        }

        // ------------------------------------------------------------ frame

        /** Before anything renders (mods read their clock while rendering): integration clocks move to this frame. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onRenderFrameFirst(RenderFrameEvent.Pre event) {
            ReplayCompat.Events.onRenderFrameFirst(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onCompatRenderFrame(RenderFrameEvent.Pre event) {
            ReplayCompat.Events.onRenderFrame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onOverlayRenderFrame(RenderFrameEvent.Pre event) {
            OverlayRenderer.onRenderFrame();
        }

        @SubscribeEvent
        public static void onEntityToolRenderFrame(RenderFrameEvent.Pre event) {
            EntityTool.onRenderFrame();
        }

        @SubscribeEvent
        public static void onOverlayToolRenderFrame(RenderFrameEvent.Pre event) {
            OverlayPlacementTool.onRenderFrame();
        }

        @SubscribeEvent
        public static void onPlacementRenderFrame(RenderFrameEvent.Pre event) {
            PlacementTool.onRenderFrame();
        }

        @SubscribeEvent
        public static void onFrameEnd(RenderFrameEvent.Post event) {
            FxEngine.onFrameEnd();
        }

        // ------------------------------------------------------------ level

        /**
         * The level stages run inside the main frame graph pass with the view matrix on the model view stack, and
         * vanilla draws its gizmos through the same immediate buffers at this point.
         */
        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.getMainCamera();
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            OverlayRenderer.onRenderLevel(true, false, camera, partialTick);
            PathRenderer.onRenderLevel(camera, event.getPoseStack(), partialTick);
            EntityTool.onRenderLevel(camera, event.getPoseStack());
            OverlayPlacementTool.onRenderLevel(camera);
            PlacementTool.onRenderLevel(camera, event.getPoseStack());
        }

        /** Fallback of the overlays when a chunk renderer skipped the stage above. */
        @SubscribeEvent
        public static void onRenderLevelLate(RenderLevelStageEvent.AfterTranslucentParticles event) {
            Minecraft mc = Minecraft.getInstance();
            OverlayRenderer.onRenderLevel(false, true, mc.gameRenderer.getMainCamera(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            OverlayPlacementTool.onRenderGui(event.getGuiGraphics());
        }

        // ------------------------------------------------------------ ticks

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            SkinLibrary.onTick();
            AudioPlayer.onTick();
            ModelRenderer.onTick();
            OverlayRenderer.onTick();
        }
    }
}
