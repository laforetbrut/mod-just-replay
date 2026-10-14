package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.gui.EditorScreen;
import com.vyrriox.justreplay.gui.ReplayHud;
import com.vyrriox.justreplay.gui.ReplayListScreen;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.nio.file.Path;

/**
 * Client wiring: key bindings, HUD layer, ticks, frame hooks and menu buttons.
 *
 * @author vyrriox
 */
public final class JustReplayClient {
    private JustReplayClient() {
    }

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        public static void onKeys(RegisterKeyMappingsEvent event) {
            JRKeys.register(event);
        }

        @SubscribeEvent
        public static void onLayers(RegisterGuiLayersEvent event) {
            event.registerAboveAll(JustReplay.id("hud"), ReplayHud::render);
        }
    }

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            Recorder recorder = Recorder.active();
            if (recorder != null) {
                recorder.clientTick(mc);
            }
            ReplaySession.clientTick(mc);
            handleKeys(mc);
            SelfTest.tick(mc);
        }

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            ReplaySession.onLoggingIn(event.getConnection());
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            if (ReplaySession.current() != null) {
                // Logouts also fire while the replay world is loading; only the replay connection ends a session.
                if (ReplaySession.isReplayConnection(event.getConnection())) {
                    ReplaySession.onLoggingOut();
                }
                return;
            }
            if (Recorder.isRecording()) {
                Path saved = Recorder.stop();
                if (saved != null) {
                    toast("justreplay.toast.saved", saved.getFileName().toString());
                }
            }
        }

        /** Recorded overlays stay; the camera's own HUD and other mods' HUDs would cover the timeline. */
        private static final java.util.Set<net.minecraft.resources.ResourceLocation> REPLAY_LAYERS = java.util.Set.of(
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.TITLE,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.SUBTITLE_OVERLAY,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.BOSS_OVERLAY,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.SCOREBOARD_SIDEBAR,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.TAB_LIST,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.OVERLAY_MESSAGE,
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.DEBUG_OVERLAY,
                JustReplay.id("hud"));

        @SubscribeEvent
        public static void onGuiLayer(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre event) {
            if (!ReplaySession.isRunning()) {
                return;
            }
            if (JRConfig.CLEAN_HUD.get() && !REPLAY_LAYERS.contains(event.getName())) {
                event.setCanceled(true);
            } else if (Minecraft.getInstance().screen instanceof EditorScreen && event.getName().equals(
                    net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT)) {
                // The recorded chat would cover the editor panels.
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRenderFramePre(RenderFrameEvent.Pre event) {
            CameraController.onRenderFrame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onRenderFramePost(RenderFrameEvent.Post event) {
            VideoExporter.onFrameRendered(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        /** Spectator flying widens the view; keyframes store the configured FOV, so keep the camera at exactly that. */
        @SubscribeEvent
        public static void onFovModifier(net.neoforged.neoforge.client.event.ComputeFovModifierEvent event) {
            if (ReplaySession.isRunning()) {
                event.setNewFovModifier(1.0F);
            }
        }

        @SubscribeEvent
        public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            Float roll = CameraController.roll();
            if (roll != null) {
                event.setRoll(roll);
            }
        }

        @SubscribeEvent
        public static void onFov(ViewportEvent.ComputeFov event) {
            Double fov = CameraController.fov();
            if (fov != null && event.usedConfiguredFov()) {
                event.setFOV(fov);
            }
        }

        /** Recorded packets must not change the screen: keeps the editor open across seeks and blocks mod GUIs. */
        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            if (Playback.isDispatching()) {
                event.setCanceled(true);
            } else if (VideoExporter.isActive() && Minecraft.getInstance().level != null) {
                // Esc cancels the export; any other screen would be burnt into the video.
                if (event.getNewScreen() instanceof PauseScreen) {
                    VideoExporter.cancel();
                    toast("justreplay.export.cancelled", "");
                } else if (event.getNewScreen() != null) {
                    event.setCanceled(true);
                }
            }
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getScreen() instanceof TitleScreen title) {
                int x = title.width / 2 + 104;
                int y = title.height / 4 + 48;
                event.addListener(Button.builder(Component.translatable("justreplay.button.replays"),
                        b -> mc.setScreen(new ReplayListScreen(title))).bounds(x, y, 72, 20).build());
            } else if (event.getScreen() instanceof PauseScreen pause && pause.showsPauseMenu()) {
                if (ReplaySession.isRunning()) {
                    event.addListener(Button.builder(Component.translatable("justreplay.button.editor"),
                            b -> mc.setScreen(new EditorScreen(ReplaySession.current()))).bounds(4, 4, 110, 20).build());
                } else {
                    Component label = Component.translatable(Recorder.isRecording() ? "justreplay.button.stop" : "justreplay.button.record");
                    event.addListener(Button.builder(label, b -> {
                        toggleRecording();
                        mc.setScreen(null);
                    }).bounds(4, 4, 110, 20).build());
                }
            }
        }
    }

    private static void toggleRecording() {
        if (ReplaySession.current() != null) {
            return;
        }
        if (Recorder.isRecording()) {
            Path saved = Recorder.stop();
            if (saved != null) {
                toast("justreplay.toast.saved", saved.getFileName().toString());
            }
        } else if (Recorder.startManual()) {
            toast("justreplay.toast.started", "");
        } else {
            toast("justreplay.toast.failed", "");
        }
    }

    private static void toast(String key, String detail) {
        Minecraft mc = Minecraft.getInstance();
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.translatable(key),
                detail.isEmpty() ? null : Component.literal(detail));
    }

    private static void handleKeys(Minecraft mc) {
        while (JRKeys.RECORD.consumeClick()) {
            toggleRecording();
        }
        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning() || VideoExporter.isActive()) {
            consumeAll();
            return;
        }
        Playback playback = session.playback();
        int step = JRConfig.SEEK_STEP_SECONDS.get() * 20;
        while (JRKeys.EDITOR.consumeClick()) {
            mc.setScreen(new EditorScreen(session));
        }
        while (JRKeys.PLAY_PAUSE.consumeClick()) {
            if (playback.cursor() >= playback.duration()) {
                playback.seek(0);
            }
            playback.setPaused(!playback.isPaused());
        }
        while (JRKeys.SEEK_BACK.consumeClick()) {
            playback.seek(playback.cursor() - step);
        }
        while (JRKeys.SEEK_FORWARD.consumeClick()) {
            playback.seek(playback.cursor() + step);
        }
        while (JRKeys.SPEED_UP.consumeClick()) {
            playback.changeSpeed(1);
        }
        while (JRKeys.SPEED_DOWN.consumeClick()) {
            playback.changeSpeed(-1);
        }
        while (JRKeys.CAMERA_PATH.consumeClick()) {
            session.setCameraPath(!session.isCameraPathActive());
        }
        while (JRKeys.TOGGLE_HUD.consumeClick()) {
            session.setHudVisible(!session.isHudVisible());
        }
        while (JRKeys.RECORDER_VIEW.consumeClick()) {
            session.jumpToRecorder(mc);
        }
        while (JRKeys.MARKER.consumeClick()) {
            int tick = playback.cursor();
            session.project().markers.add(new ReplayProject.Marker(tick, "Marker " + (session.project().markers.size() + 1)));
            session.saveProject();
        }
        while (JRKeys.CAMERA_KEYFRAME.consumeClick()) {
            LocalPlayer p = mc.player;
            if (p != null) {
                int tick = playback.cursor();
                session.project().cameraKeyframes.removeIf(k -> k.tick == tick);
                session.project().cameraKeyframes.add(new ReplayProject.CameraKeyframe(tick, p.getX(), p.getY(), p.getZ(),
                        p.getYRot(), p.getXRot(), 0, mc.options.fov().get()));
                session.saveProject();
                toast("justreplay.toast.keyframe", com.vyrriox.justreplay.gui.Timeline.formatTicks(tick));
            }
        }
    }

    private static void consumeAll() {
        for (var key : new net.minecraft.client.KeyMapping[]{JRKeys.EDITOR, JRKeys.PLAY_PAUSE, JRKeys.SEEK_BACK, JRKeys.SEEK_FORWARD,
                JRKeys.SPEED_UP, JRKeys.SPEED_DOWN, JRKeys.CAMERA_KEYFRAME, JRKeys.CAMERA_PATH, JRKeys.MARKER, JRKeys.TOGGLE_HUD,
                JRKeys.RECORDER_VIEW}) {
            while (key.consumeClick()) {
                // Playback keys do nothing outside a replay.
            }
        }
    }
}
