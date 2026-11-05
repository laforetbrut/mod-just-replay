package com.vyrriox.justreplay.client.hook;

import com.vyrriox.justreplay.client.JustReplayClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Loader-free entry points of the client hooks. Each loader's glue calls these from its own events or callbacks, with
 * neutral arguments. A boolean named {@code cancels} or {@code hides} asks the caller to stop what the hook is about.
 *
 * @author vyrriox
 */
public final class ClientHooks {
    private ClientHooks() {
    }

    public static void registerKeys(Consumer<KeyMapping> registrar) {
        JustReplayClient.registerKeys(registrar);
    }

    public static void registerGuiLayers(BiConsumer<ResourceLocation, LayeredDraw.Layer> registrar) {
        JustReplayClient.registerGuiLayers(registrar);
    }

    public static boolean hidesNameTag(Entity entity) {
        return JustReplayClient.hidesNameTag(entity);
    }

    public static void onClientTickPre() {
        JustReplayClient.onTickPre();
    }

    public static void onClientTickPost() {
        JustReplayClient.onTick();
    }

    public static void onLoggingIn(Connection connection) {
        JustReplayClient.onLoggingIn(connection);
    }

    public static void onLoggingOut(Connection connection) {
        JustReplayClient.onLoggingOut(connection);
    }

    /**
     * @param replayLayer the layer stays with the clean HUD setting: a recorded overlay or one of ours
     * @param chatLayer   the layer is the chat
     */
    public static boolean hidesGuiLayer(boolean replayLayer, boolean chatLayer) {
        return JustReplayClient.hidesGuiLayer(replayLayer, chatLayer);
    }

    public static void onFramePre(float partialTick) {
        JustReplayClient.onRenderFramePre(partialTick);
    }

    public static void onFramePost(float partialTick) {
        JustReplayClient.onRenderFramePost(partialTick);
    }

    /** True when the FOV modifier must stay at exactly 1. */
    public static boolean locksFovModifier() {
        return JustReplayClient.locksFovModifier();
    }

    /** Camera roll to apply, or null to leave it. */
    public static Float cameraRoll() {
        return JustReplayClient.cameraRoll();
    }

    /** FOV to apply, or null to leave it. */
    public static Double cameraFov(boolean usedConfiguredFov) {
        return JustReplayClient.cameraFov(usedConfiguredFov);
    }

    public static boolean cancelsScreen(Screen screen) {
        return JustReplayClient.cancelsScreen(screen);
    }

    public static boolean cancelsEntityTick(Entity entity) {
        return JustReplayClient.cancelsEntityTick(entity);
    }

    /** Called on an attack key press; also the moment a left click picks an entity to view the replay through. */
    public static boolean cancelsAttack() {
        return JustReplayClient.cancelsAttack();
    }

    public static boolean cancelsBlockHighlight() {
        return JustReplayClient.cancelsBlockHighlight();
    }

    /** @return false to drop the sound */
    public static boolean onSound(SoundInstance sound) {
        return JustReplayClient.onPlaySound(sound);
    }

    public static void onScreenInit(Screen screen, Consumer<AbstractWidget> addWidget) {
        JustReplayClient.onScreenInit(screen, addWidget);
    }
}
