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
         * The 1.21.1 hook drew the editor overlays, the camera path and the placement tools inside the level stages with
         * immediate mode buffers. 26.1 renders the level from extracted render state, so those draws are not ported yet.
         */
        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentFeatures event) {
            if (!LEVEL_DRAW_LOGGED) {
                LEVEL_DRAW_LOGGED = true;
                com.vyrriox.justreplay.JustReplay.LOGGER.warn("Level overlays (camera path, placement tools, image overlays) are not ported to 26.1 yet");
            }
        }

        private static boolean LEVEL_DRAW_LOGGED;

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
