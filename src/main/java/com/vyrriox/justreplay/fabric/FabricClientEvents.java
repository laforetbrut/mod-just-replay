package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.client.Frames;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fabric callbacks of the client wiring, forwarded to the loader-free classes in the order of the other loaders.
 *
 * @author vyrriox
 */
final class FabricClientEvents {
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
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> FabricRenderHooks.onTranslucent(
                context.camera(), context.matrixStack() != null ? context.matrixStack() : new PoseStack(),
                Frames.partialTick()));
        WorldRenderEvents.LAST.register(context -> FabricRenderHooks.onParticles(
                context.camera(), context.matrixStack() != null ? context.matrixStack() : new PoseStack(),
                Frames.partialTick()));
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
}
