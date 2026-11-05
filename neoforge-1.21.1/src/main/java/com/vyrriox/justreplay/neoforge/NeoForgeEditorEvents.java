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
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

import java.io.IOException;

/**
 * NeoForge events of the editor, the replay tools and the integrations, forwarded to their loader-free classes.
 *
 * @author vyrriox
 */
public final class NeoForgeEditorEvents {
    private NeoForgeEditorEvents() {
    }

    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void onShaders(RegisterShadersEvent event) throws IOException {
            FxEngine.registerShaders(event.getResourceProvider(), event::registerShader);
        }
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

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            RenderLevelStageEvent.Stage stage = event.getStage();
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            boolean translucent = stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
            OverlayRenderer.onRenderLevel(translucent, stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES,
                    event.getCamera(), partialTick);
            if (translucent) {
                PathRenderer.onRenderLevel(event.getCamera(), event.getPoseStack(), partialTick);
                EntityTool.onRenderLevel(event.getCamera(), event.getPoseStack());
                OverlayPlacementTool.onRenderLevel(event.getCamera());
                PlacementTool.onRenderLevel(event.getCamera(), event.getPoseStack());
            }
        }

        @SubscribeEvent
        public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
            if (ModelRenderer.onRenderLiving(event.getEntity(), event.getPartialTick(), event.getPoseStack(),
                    event.getMultiBufferSource(), event.getPackedLight())) {
                event.setCanceled(true);
            }
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

        // ------------------------------------------------------------ sound

        @SubscribeEvent
        public static void onPlaySound(PlaySoundEvent event) {
            SoundInstance sound = event.getSound();
            SoundInstance result = AudioPlayer.onPlaySound(sound);
            if (result != sound) {
                event.setSound(result);
            }
        }

        // ------------------------------------------------------------ input

        @SubscribeEvent
        public static void onScroll(InputEvent.MouseScrollingEvent event) {
            double x = event.getScrollDeltaX();
            double y = event.getScrollDeltaY();
            if (EntityTool.onScroll(x, y) | OverlayPlacementTool.onScroll(x, y) | PlacementTool.onScroll(x, y)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onMouse(InputEvent.MouseButton.Pre event) {
            int button = event.getButton();
            int action = event.getAction();
            if (EntityTool.onMouse(button, action) | OverlayPlacementTool.onMouse(button, action) | PlacementTool.onMouse(button, action)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            if (EntityTool.onScreenOpening(event.getNewScreen()) | OverlayPlacementTool.onScreenOpening(event.getNewScreen())
                    | PlacementTool.onScreenOpening(event.getNewScreen())) {
                event.setCanceled(true);
            }
        }
    }
}
