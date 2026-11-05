package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.Connection;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric callbacks of the client wiring, forwarded to the loader-free classes in the order of the other loaders.
 *
 * @author vyrriox
 */
final class FabricClientEvents {
    /** Above every vanilla HUD layer, which are stacked 200 apart in depth. */
    private static final float HUD_DEPTH = 6000.0F;

    private FabricClientEvents() {
    }

    static void register() {
        ClientHooks.registerKeys(KeyBindingHelper::registerKeyBinding);
        ClientTickEvents.START_CLIENT_TICK.register(client -> ClientHooks.onClientTickPre());
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTickEnd());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientHooks.onLoggingIn(handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect(handler.getConnection()));
        CoreShaderRegistrationCallback.EVENT.register(context -> FxEngine.registerShaders(
                (name, onLoaded) -> context.register(name, DefaultVertexFormat.POSITION, onLoaded)));
        registerHud();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(FabricClientEvents::afterTranslucent);
        // Still inside the model view of the level, unlike END. Overlays only draw here when the pass above did not run.
        WorldRenderEvents.LAST.register(context -> OverlayRenderer.onRenderLevel(false, true, context.camera(),
                context.tickCounter().getGameTimeDeltaPartialTick(false)));
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hit) -> !(hit instanceof BlockHitResult) || !ClientHooks.cancelsBlockHighlight());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ClientHooks.onScreenInit(screen, Screens.getButtons(screen)::add));
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
        List<LayeredDraw.Layer> layers = new ArrayList<>();
        ClientHooks.registerGuiLayers((name, layer) -> layers.add(layer));
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, HUD_DEPTH);
            for (LayeredDraw.Layer layer : layers) {
                layer.render(graphics, delta);
            }
            OverlayPlacementTool.onRenderGui(graphics);
            graphics.pose().popPose();
        });
    }

    private static void afterTranslucent(WorldRenderContext context) {
        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
        PoseStack pose = context.matrixStack() != null ? context.matrixStack() : new PoseStack();
        OverlayRenderer.onRenderLevel(true, false, context.camera(), partialTick);
        PathRenderer.onRenderLevel(context.camera(), pose, partialTick);
        EntityTool.onRenderLevel(context.camera(), pose);
        OverlayPlacementTool.onRenderLevel(context.camera());
        PlacementTool.onRenderLevel(context.camera(), pose);
    }
}
