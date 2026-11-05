package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Set;

/**
 * NeoForge events of the client wiring, forwarded to {@link ClientHooks}.
 *
 * @author vyrriox
 */
public final class NeoForgeClientEvents {
    private NeoForgeClientEvents() {
    }

    public static final class ModEvents {
        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            java.util.Set<net.minecraft.client.KeyMapping.Category> categories = new java.util.HashSet<>();
            ClientHooks.registerKeys(key -> {
                if (categories.add(key.getCategory())) {
                    event.registerCategory(key.getCategory());
                }
                event.register(key);
            });
        }

        @SubscribeEvent
        public static void onLayers(RegisterGuiLayersEvent event) {
            ClientHooks.registerGuiLayers((id, layer) -> event.registerAboveAll(id, layer::render));
        }
    }

    public static final class GameEvents {
        /** Layers kept by the clean HUD setting. */
        private static final Set<Identifier> REPLAY_LAYERS = Set.of(
                VanillaGuiLayers.CHAT,
                VanillaGuiLayers.TITLE,
                VanillaGuiLayers.SUBTITLE_OVERLAY,
                VanillaGuiLayers.BOSS_OVERLAY,
                VanillaGuiLayers.SCOREBOARD_SIDEBAR,
                VanillaGuiLayers.TAB_LIST,
                VanillaGuiLayers.OVERLAY_MESSAGE,
                JustReplay.id("hud"),
                JustReplay.id("placement"),
                JustReplay.id("entity_tool"));

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            ClientHooks.onClientTickPost();
        }

        @SubscribeEvent
        public static void onTickPre(ClientTickEvent.Pre event) {
            ClientHooks.onClientTickPre();
        }

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            ClientHooks.onLoggingIn(event.getConnection());
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientHooks.onLoggingOut(event.getConnection());
        }

        @SubscribeEvent
        public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
            Identifier name = event.getName();
            if (ClientHooks.hidesGuiLayer(REPLAY_LAYERS.contains(name), name.equals(VanillaGuiLayers.CHAT))) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRenderFramePre(RenderFrameEvent.Pre event) {
            ClientHooks.onFramePre(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onRenderFramePost(RenderFrameEvent.Post event) {
            ClientHooks.onFramePost(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onFovModifier(ComputeFovModifierEvent event) {
            if (ClientHooks.locksFovModifier()) {
                event.setNewFovModifier(1.0F);
            }
        }

        @SubscribeEvent
        public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            Float roll = ClientHooks.cameraRoll();
            if (roll != null) {
                event.setRoll(roll);
            }
        }

        @SubscribeEvent
        public static void onFov(ViewportEvent.ComputeFov event) {
            Double fov = ClientHooks.cameraFov(event.usedConfiguredFov());
            if (fov != null) {
                event.setFOV(fov.floatValue());
            }
        }

        @SubscribeEvent
        public static void onBlockHighlight(ExtractBlockOutlineRenderStateEvent event) {
            if (ClientHooks.cancelsBlockHighlight()) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            ClientHooks.onScreenInit(event.getScreen(), event::addListener);
        }
    }
}
