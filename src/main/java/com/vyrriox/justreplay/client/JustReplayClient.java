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
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.Minecraft;
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
            event.registerAboveAll(JustReplay.id("placement"), com.vyrriox.justreplay.gui.PlacementTool::renderHud);
            event.registerAboveAll(JustReplay.id("entity_tool"), com.vyrriox.justreplay.gui.EntityTool::renderHud);
        }
    }

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onNameTag(net.neoforged.neoforge.client.event.RenderNameTagEvent event) {
            if (com.vyrriox.justreplay.editor.ActorManager.hidesName(event.getEntity())) {
                event.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
        }

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            Recorder.clientTickAll(mc);
            ReplaySession.clientTick(mc);
            handleKeys(mc);
            SelfTest.tick(mc);
            SceneRecorder.tick(mc);
            com.vyrriox.justreplay.compat.ReplayCompat.clientTick(mc);
        }

        @SubscribeEvent
        public static void onTickPre(ClientTickEvent.Pre event) {
            suppressHotbarKeys(Minecraft.getInstance());
        }

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            if (JRConfig.REPLAY_BUFFER.get() && JRConfig.BUFFER_MINUTES.get() > 30 && !ReplaySession.isReplayConnection(event.getConnection())) {
                // Long instant replay windows cost memory: say so once per world.
                int minutes = JRConfig.BUFFER_MINUTES.get();
                toast(Component.translatable("justreplay.toast.buffer_ram", minutes),
                        Component.translatable("justreplay.toast.buffer_ram.detail", Math.max(1, minutes * 10 / 10), minutes * 25 / 10));
            }
            com.vyrriox.justreplay.editor.CameraRig.reset();
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
            Path saved = Recorder.endCapture();
            if (saved != null) {
                toast("justreplay.toast.saved", saved.getFileName().toString());
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
                JustReplay.id("hud"),
                JustReplay.id("placement"),
                JustReplay.id("entity_tool"));

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
            com.vyrriox.justreplay.replay.SectionRepair.beforeFrame(Minecraft.getInstance());
            ReplaySession session = ReplaySession.current();
            if (session != null && session.spectate().isActive() && session.isCameraPathActive()) {
                // A camera path or an export started this tick: never render it from a spectated entity.
                session.spectate().stop(Minecraft.getInstance());
            }
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

        /** Actors are puppets: their own AI/tick code (often server-minded in mods) never runs. */
        @SubscribeEvent
        public static void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
            if (event.getEntity().level().isClientSide() && ReplaySession.isRunning()
                    && com.vyrriox.justreplay.editor.ActorManager.isActorEntity(event.getEntity())
                    && !(event.getEntity() instanceof net.minecraft.client.player.RemotePlayer)) {
                event.setCanceled(true);
            }
        }

        /**
         * Attacking in a replay would reach the host server; a left click on an entity views the replay through it
         * instead.
         */
        @SubscribeEvent
        public static void onInteractionKey(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isAttack() || !ReplaySession.isRunning()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            Minecraft mc = Minecraft.getInstance();
            ReplaySession session = ReplaySession.current();
            if (session != null && mc.screen == null && !session.isCameraPathActive()) {
                session.spectate(mc, com.vyrriox.justreplay.replay.SpectateCamera.pick(mc));
            }
        }

        /** A spectated player keeps its build ability, which would draw the block outline over the view. */
        @SubscribeEvent
        public static void onBlockHighlight(net.neoforged.neoforge.client.event.RenderHighlightEvent.Block event) {
            ReplaySession session = ReplaySession.current();
            if (session != null && ReplaySession.isRunning() && session.spectate().isActive()) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onPlaySound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
            VideoExporter.onSound(event);
            SelfTest.onSound(event);
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getScreen() instanceof TitleScreen title) {
                int x = title.width / 2 + 104;
                int y = title.height / 4 + 48;
                event.addListener(new GlassButton(x, y, 72, 20, Component.translatable("justreplay.button.replays"),
                        b -> mc.setScreen(new ReplayListScreen(title))).icon(Icon.FILM).iconColor(Theme.ACCENT)
                        .tip(Component.translatable("justreplay.button.replays.tip")));
            } else if (event.getScreen() instanceof PauseScreen pause && pause.showsPauseMenu()) {
                if (ReplaySession.isRunning()) {
                    event.addListener(new GlassButton(4, 4, 110, 20, Component.translatable("justreplay.button.editor"),
                            b -> mc.setScreen(new EditorScreen(ReplaySession.current()))).icon(Icon.FILM).iconColor(Theme.ACCENT));
                } else {
                    boolean recording = Recorder.isRecording();
                    Component label = Component.translatable(recording ? "justreplay.button.stop" : "justreplay.button.record");
                    event.addListener(new GlassButton(4, 4, 110, 20, label, b -> {
                        toggleRecording();
                        mc.setScreen(null);
                    }).icon(recording ? Icon.STOP : Icon.RECORD).iconColor(Theme.RECORD)
                            .tip(Component.translatable("justreplay.button.record.tip", JRKeys.RECORD.getTranslatedKeyMessage())));
                }
            }
        }
    }

    /** Config screen in the mod list; only called on the client. */
    public static void registerConfigScreen(net.neoforged.fml.ModContainer container) {
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
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
        Icon icon = switch (key) {
            case "justreplay.toast.started" -> Icon.RECORD;
            case "justreplay.toast.saved" -> Icon.SAVE;
            case "justreplay.toast.keyframe" -> Icon.KEY;
            case "justreplay.toast.cut" -> Icon.CUT;
            case "justreplay.export.cancelled" -> Icon.FILM;
            case "justreplay.editor.cam.not_path" -> Icon.CAMERA;
            case "justreplay.toast.failed" -> Icon.CLOSE;
            default -> Icon.CHECK;
        };
        int accent = switch (key) {
            case "justreplay.toast.started" -> Theme.RECORD;
            case "justreplay.toast.saved" -> Theme.SUCCESS;
            case "justreplay.toast.failed", "justreplay.editor.cam.not_path" -> Theme.WARNING;
            default -> Theme.ACCENT;
        };
        GlassToast.show(icon, accent, Component.translatable(key), detail.isEmpty() ? null : Component.literal(detail));
    }

    private static boolean savingBuffer;

    /** Saves the instant replay buffer off the client thread; the toast follows when the files are written. */
    private static void saveBuffer(Minecraft mc) {
        if (ReplaySession.current() != null || savingBuffer) {
            return;
        }
        var saving = Recorder.saveBuffer();
        if (saving == null) {
            toast(Component.translatable("justreplay.toast.buffer_off"), Component.translatable("justreplay.toast.buffer_off.hint"));
            return;
        }
        savingBuffer = true;
        saving.whenComplete((saved, error) -> mc.execute(() -> {
            savingBuffer = false;
            if (error != null || saved == null) {
                JustReplay.LOGGER.warn("Cannot save the replay buffer: {}", String.valueOf(error));
                toast("justreplay.toast.buffer_failed", "");
                return;
            }
            float minutes = saved.durationTicks() / 1200.0F;
            String length = minutes >= 1.0F && Math.abs(minutes - Math.round(minutes)) < 0.05F
                    ? Integer.toString(Math.round(minutes))
                    : String.format(java.util.Locale.ROOT, "%.1f", minutes);
            toast(Component.translatable("justreplay.toast.buffer_saved", length),
                    Component.literal(saved.dir().getFileName().toString()));
        }));
    }

    /** Starts a recording of the configured length, or stops the running recording. */
    private static void toggleTimedRecording() {
        if (ReplaySession.current() != null) {
            return;
        }
        if (Recorder.isRecording()) {
            toggleRecording();
            return;
        }
        int minutes = JRConfig.NEXT_MINUTES.get();
        if (Recorder.startTimed(minutes * 1200)) {
            toast(Component.translatable("justreplay.toast.next_started", minutes), null);
        } else {
            toast("justreplay.toast.failed", "");
        }
    }

    private static void toast(Component title, Component detail) {
        GlassToast.show(Icon.RECORD, Theme.RECORD, title, detail);
    }

    private static void handleKeys(Minecraft mc) {
        while (JRKeys.RECORD.consumeClick()) {
            toggleRecording();
        }
        while (JRKeys.SAVE_BUFFER.consumeClick()) {
            saveBuffer(mc);
        }
        while (JRKeys.RECORD_NEXT.consumeClick()) {
            toggleTimedRecording();
        }
        Recorder recorder = Recorder.active();
        if (recorder != null && recorder.timedRecordingFinished()) {
            Path saved = Recorder.stop();
            if (saved != null) {
                toast("justreplay.toast.next_done", saved.getFileName().toString());
            }
        }
        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning() || VideoExporter.isActive()) {
            consumeAll();
            return;
        }
        Playback playback = session.playback();
        int step = JRConfig.SEEK_STEP_SECONDS.get() * 20;
        while (JRKeys.EDITOR.consumeClick()) {
            if (com.vyrriox.justreplay.gui.PlacementTool.isActive()) {
                // The editor key while placing an actor goes back to the editor without placing it.
                com.vyrriox.justreplay.gui.PlacementTool.active().cancel();
            } else if (com.vyrriox.justreplay.gui.EntityTool.isActive()) {
                com.vyrriox.justreplay.gui.EntityTool.cancel();
            } else {
                mc.setScreen(new EditorScreen(session));
            }
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
            session.toggleRecorderView(mc);
        }
        while (JRKeys.STOP_SPECTATING.consumeClick()) {
            session.spectate().stop(mc);
        }
        while (JRKeys.MARKER.consumeClick()) {
            int tick = playback.cursor();
            session.project().markers.add(new ReplayProject.Marker(tick, "Marker " + (session.project().markers.size() + 1)));
            session.saveProject();
        }
        while (JRKeys.CAMERA_KEYFRAME.consumeClick()) {
            LocalPlayer p = mc.player;
            ReplayProject.Camera camera = session.project().selected();
            if (p != null && camera != null) {
                if (!camera.isPath()) {
                    toast("justreplay.editor.cam.not_path", camera.name);
                    continue;
                }
                int tick = playback.cursor();
                camera.keyframes.removeIf(k -> k.tick == tick);
                camera.keyframes.add(new ReplayProject.CameraKeyframe(tick, p.getX(), p.getY(), p.getZ(),
                        p.getYRot(), p.getXRot(), 0, mc.options.fov().get()));
                session.saveProject();
                toast("justreplay.toast.keyframe", camera.name + "  " + com.vyrriox.justreplay.gui.Timeline.formatTicks(tick));
            }
        }
        for (int i = 0; i < JRKeys.CUT_TO.length; i++) {
            while (JRKeys.CUT_TO[i].consumeClick()) {
                ReplayProject.Camera camera = com.vyrriox.justreplay.gui.CameraPanel.quickSwitch(session, i);
                if (camera != null && playback.isPaused()) {
                    // While playing the switch is visible at once; when paused, confirm it.
                    toast("justreplay.toast.cut", camera.name);
                }
            }
        }
    }

    /**
     * The live switching keys default to 1-9, which also select hotbar slots; in a replay the free camera is a
     * spectator, for which those keys would open the spectator menu over the view.
     */
    private static void suppressHotbarKeys(Minecraft mc) {
        if (!ReplaySession.isRunning()) {
            return;
        }
        for (net.minecraft.client.KeyMapping slot : mc.options.keyHotbarSlots) {
            while (slot.consumeClick()) {
                // Swallowed: hotbar keys have no use in a replay.
            }
        }
    }

    private static void consumeAll() {
        for (var key : new net.minecraft.client.KeyMapping[]{JRKeys.EDITOR, JRKeys.PLAY_PAUSE, JRKeys.SEEK_BACK, JRKeys.SEEK_FORWARD,
                JRKeys.SPEED_UP, JRKeys.SPEED_DOWN, JRKeys.CAMERA_KEYFRAME, JRKeys.CAMERA_PATH, JRKeys.MARKER, JRKeys.TOGGLE_HUD,
                JRKeys.RECORDER_VIEW, JRKeys.STOP_SPECTATING}) {
            while (key.consumeClick()) {
                // Playback keys do nothing outside a replay.
            }
        }
        for (var key : JRKeys.CUT_TO) {
            while (key.consumeClick()) {
                // Live switching only acts inside a replay.
            }
        }
    }
}
