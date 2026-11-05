package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

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
            ClientHooks.registerKeys(event::register);
        }

        @SubscribeEvent
        public static void onLayers(RegisterGuiLayersEvent event) {
            ClientHooks.registerGuiLayers(event::registerAboveAll);
        }
    }

    public static final class GameEvents {
        /** Layers kept by the clean HUD setting. */
        private static final Set<ResourceLocation> REPLAY_LAYERS = Set.of(
                VanillaGuiLayers.CHAT,
                VanillaGuiLayers.TITLE,
                VanillaGuiLayers.SUBTITLE_OVERLAY,
                VanillaGuiLayers.BOSS_OVERLAY,
                VanillaGuiLayers.SCOREBOARD_SIDEBAR,
                VanillaGuiLayers.TAB_LIST,
                VanillaGuiLayers.OVERLAY_MESSAGE,
                VanillaGuiLayers.DEBUG_OVERLAY,
                JustReplay.id("hud"),
                JustReplay.id("placement"),
                JustReplay.id("entity_tool"));

        @SubscribeEvent
        public static void onNameTag(RenderNameTagEvent event) {
            if (ClientHooks.hidesNameTag(event.getEntity())) {
                event.setCanRender(TriState.FALSE);
            }
        }

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
            ResourceLocation name = event.getName();
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
                event.setFOV(fov);
            }
        }

        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            if (ClientHooks.cancelsScreen(event.getNewScreen())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onEntityTick(EntityTickEvent.Pre event) {
            if (ClientHooks.cancelsEntityTick(event.getEntity())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
            if (event.isAttack() && ClientHooks.cancelsAttack()) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void onBlockHighlight(RenderHighlightEvent.Block event) {
            if (ClientHooks.cancelsBlockHighlight()) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onPlaySound(PlaySoundEvent event) {
            if (!ClientHooks.onSound(event.getSound())) {
                event.setSound(null);
            }
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            ClientHooks.onScreenInit(event.getScreen(), event::addListener);
        }
    }
}
