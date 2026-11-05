package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Fabric callbacks of the client wiring, forwarded to the loader-free classes in the order of the other loaders.
 *
 * @author vyrriox
 */
final class FabricClientEvents {
    /** Vanilla elements the clean HUD setting hides. */
    private static final List<Identifier> PLAYER_ELEMENTS = List.of(
            VanillaHudElements.MISC_OVERLAYS,
            VanillaHudElements.CROSSHAIR,
            VanillaHudElements.SPECTATOR_MENU,
            VanillaHudElements.HOTBAR,
            VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH,
            VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.HELD_ITEM_TOOLTIP,
            VanillaHudElements.SPECTATOR_TOOLTIP,
            VanillaHudElements.MOB_EFFECTS,
            VanillaHudElements.SLEEP,
            VanillaHudElements.DEMO_TIMER);
    /** Vanilla elements it keeps, as a recording shows them. */
    private static final List<Identifier> REPLAY_ELEMENTS = List.of(
            VanillaHudElements.BOSS_BAR,
            VanillaHudElements.SCOREBOARD,
            VanillaHudElements.OVERLAY_MESSAGE,
            VanillaHudElements.TITLE_AND_SUBTITLE,
            VanillaHudElements.PLAYER_LIST,
            VanillaHudElements.SUBTITLES);

    private FabricClientEvents() {
    }

    static void register() {
        ClientHooks.registerKeys(KeyMappingHelper::registerKeyMapping);
        ClientTickEvents.START_CLIENT_TICK.register(client -> ClientHooks.onClientTickPre());
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTickEnd());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientHooks.onLoggingIn(handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect(handler.getConnection()));
        registerHud();
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(FabricClientEvents::afterTranslucent);
        // Fallback of the overlays when a chunk renderer skipped the stage above.
        LevelRenderEvents.END_MAIN.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            OverlayRenderer.onRenderLevel(false, true, mc.gameRenderer.getMainCamera(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        });
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> !ClientHooks.cancelsBlockHighlight());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ClientHooks.onScreenInit(screen, Screens.getWidgets(screen)::add));
    }

    private static void onTickEnd() {
        ClientHooks.onClientTickPost();
        SkinLibrary.onTick();
        AudioPlayer.onTick();
        ModelRenderer.onTick();
        OverlayRenderer.onTick();
    }

    /** The network thread can close a connection; the handlers belong to the render thread. */
    private static void onDisconnect(Connection connection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            ClientHooks.onLoggingOut(connection);
        } else {
            minecraft.execute(() -> ClientHooks.onLoggingOut(connection));
        }
    }

    private static void registerHud() {
        for (Identifier element : PLAYER_ELEMENTS) {
            hideWhen(element, false, false);
        }
        for (Identifier element : REPLAY_ELEMENTS) {
            hideWhen(element, true, false);
        }
        hideWhen(VanillaHudElements.CHAT, true, true);
        ClientHooks.registerGuiLayers((id, layer) -> HudElementRegistry.addLast(id, layer::render));
        HudElementRegistry.addLast(JustReplay.id("overlay_tool"), (graphics, delta) -> OverlayPlacementTool.onRenderGui(graphics));
    }

    private static void hideWhen(Identifier element, boolean replayLayer, boolean chatLayer) {
        HudElementRegistry.replaceElement(element, inner -> (graphics, delta) -> {
            if (!ClientHooks.hidesGuiLayer(replayLayer, chatLayer)) {
                inner.extractRenderState(graphics, delta);
            }
        });
    }

    /**
     * The level stages run inside the main frame graph pass with the view matrix on the model view stack, and vanilla
     * draws its gizmos through the same immediate buffers at this point.
     */
    private static void afterTranslucent(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = context.poseStack();
        OverlayRenderer.onRenderLevel(true, false, camera, partialTick);
        PathRenderer.onRenderLevel(camera, pose, partialTick);
        EntityTool.onRenderLevel(camera, pose);
        OverlayPlacementTool.onRenderLevel(camera);
        PlacementTool.onRenderLevel(camera, pose);
    }
}
