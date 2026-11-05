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

import java.nio.file.Path;

/**
 * Client wiring: key bindings, HUD layer, ticks, frame hooks and menu buttons.
 *
 * @author vyrriox
 */
public final class JustReplayClient {
    private JustReplayClient() {
    }

    public static void registerKeys(java.util.function.Consumer<net.minecraft.client.KeyMapping> registrar) {
        JRKeys.register(registrar);
    }

    public static void registerGuiLayers(java.util.function.BiConsumer<net.minecraft.resources.ResourceLocation, net.minecraft.client.gui.LayeredDraw.Layer> registrar) {
        registrar.accept(JustReplay.id("hud"), ReplayHud::render);
        registrar.accept(JustReplay.id("placement"), com.vyrriox.justreplay.gui.PlacementTool::renderHud);
        registrar.accept(JustReplay.id("entity_tool"), com.vyrriox.justreplay.gui.EntityTool::renderHud);
    }

    public static boolean hidesNameTag(net.minecraft.world.entity.Entity entity) {
        return com.vyrriox.justreplay.editor.ActorManager.hidesName(entity);
    }

    public static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        Recorder.clientTickAll(mc);
        ReplaySession.clientTick(mc);
        handleKeys(mc);
        SelfTest.tick(mc);
        SceneRecorder.tick(mc);
        com.vyrriox.justreplay.compat.ReplayCompat.clientTick(mc);
    }

    public static void onTickPre() {
        suppressHotbarKeys(Minecraft.getInstance());
    }

    public static void onLoggingIn(net.minecraft.network.Connection connection) {
        if (JRConfig.REPLAY_BUFFER.get() && JRConfig.BUFFER_MINUTES.get() > 30 && !ReplaySession.isReplayConnection(connection)) {
            // Long instant replay windows cost memory: say so once per world.
            int minutes = JRConfig.BUFFER_MINUTES.get();
            toast(Component.translatable("justreplay.toast.buffer_ram", minutes),
                    Component.translatable("justreplay.toast.buffer_ram.detail", Math.max(1, minutes * 10 / 10), minutes * 25 / 10));
        }
        com.vyrriox.justreplay.editor.CameraRig.reset();
        ReplaySession.onLoggingIn(connection);
    }

    public static void onLoggingOut(net.minecraft.network.Connection connection) {
        if (ReplaySession.current() != null) {
            // Logouts also fire while the replay world is loading; only the replay connection ends a session.
            if (ReplaySession.isReplayConnection(connection)) {
                ReplaySession.onLoggingOut();
            }
            return;
        }
        Path saved = Recorder.endCapture();
        if (saved != null) {
            toast(Recorder.lastStopIncomplete() ? "justreplay.toast.incomplete" : "justreplay.toast.saved", saved.getFileName().toString());
        }
    }

    /**
     * Whether a HUD layer is hidden. Recorded overlays stay; the camera's own HUD and other mods' HUDs would cover the
     * timeline.
     *
     * @param replayLayer the layer is a recorded overlay or one of ours
     * @param chatLayer   the layer is the chat
     */
    public static boolean hidesGuiLayer(boolean replayLayer, boolean chatLayer) {
        if (!ReplaySession.isRunning()) {
            return false;
        }
        if (JRConfig.CLEAN_HUD.get() && !replayLayer) {
            return true;
        }
        // The recorded chat would cover the editor panels.
        return Minecraft.getInstance().screen instanceof EditorScreen && chatLayer;
    }

    public static void onRenderFramePre(float partialTick) {
        com.vyrriox.justreplay.replay.SectionRepair.beforeFrame(Minecraft.getInstance());
        ReplaySession session = ReplaySession.current();
        if (session != null && session.spectate().isActive() && session.isCameraPathActive()) {
            // A camera path or an export started this tick: never render it from a spectated entity.
            session.spectate().stop(Minecraft.getInstance());
        }
        CameraController.onRenderFrame(partialTick);
        com.vyrriox.justreplay.replay.WorldOverride.onRenderFrame(partialTick);
    }

    public static void onRenderFramePost(float partialTick) {
        VideoExporter.onFrameRendered(partialTick);
    }

    /** Spectator flying widens the view; keyframes store the configured FOV, so keep the camera at exactly that. */
    public static boolean locksFovModifier() {
        return ReplaySession.isRunning();
    }

    public static Float cameraRoll() {
        return CameraController.roll();
    }

    public static Double cameraFov(boolean usedConfiguredFov) {
        return usedConfiguredFov ? CameraController.fov() : null;
    }

    /** Recorded packets must not change the screen: keeps the editor open across seeks and blocks mod GUIs. */
    public static boolean cancelsScreen(net.minecraft.client.gui.screens.Screen screen) {
        if (Playback.isDispatching()) {
            return true;
        }
        if (ReplaySession.isRunning() && isPlayerOnlyScreen(screen)) {
            // The camera is not a player of the recording: its inventory and progress mean nothing in a replay.
            return true;
        }
        if (VideoExporter.isActive() && Minecraft.getInstance().level != null) {
            // Esc cancels the export; any other screen would be burnt into the video.
            if (screen instanceof PauseScreen) {
                VideoExporter.cancel();
                toast("justreplay.export.cancelled", "");
            } else if (screen != null) {
                return true;
            }
        }
        return false;
    }

    /** Screens about the player itself (inventory, containers, advancements), which a replay camera has none of. */
    private static boolean isPlayerOnlyScreen(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                || screen instanceof net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
    }

    /** Actors are puppets: their own AI/tick code (often server-minded in mods) never runs. */
    public static boolean cancelsEntityTick(net.minecraft.world.entity.Entity entity) {
        return entity.level().isClientSide() && ReplaySession.isRunning()
                && com.vyrriox.justreplay.editor.ActorManager.isActorEntity(entity)
                && !(entity instanceof net.minecraft.client.player.RemotePlayer);
    }

    /**
     * Attacking in a replay would reach the host server; a left click on an entity views the replay through it
     * instead.
     */
    public static boolean cancelsAttack() {
        if (!ReplaySession.isRunning()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        ReplaySession session = ReplaySession.current();
        if (session != null && mc.screen == null && !session.isCameraPathActive()) {
            session.spectate(mc, com.vyrriox.justreplay.replay.SpectateCamera.pick(mc));
        }
        return true;
    }

    /** A spectated player keeps its build ability, which would draw the block outline over the view. */
    public static boolean cancelsBlockHighlight() {
        ReplaySession session = ReplaySession.current();
        return session != null && ReplaySession.isRunning() && session.spectate().isActive();
    }

    public static boolean onPlaySound(net.minecraft.client.resources.sounds.SoundInstance sound) {
        if (sound == null) {
            return true;
        }
        if (!VideoExporter.onSound(sound)) {
            return false;
        }
        SelfTest.onSound(sound);
        return true;
    }

    public static void onScreenInit(net.minecraft.client.gui.screens.Screen screen, java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> addWidget) {
        Minecraft mc = Minecraft.getInstance();
        if (screen instanceof TitleScreen title) {
            int x = title.width / 2 + 104;
            int y = title.height / 4 + 48;
            addWidget.accept(new GlassButton(x, y, 72, 20, Component.translatable("justreplay.button.replays"),
                    b -> mc.setScreen(new ReplayListScreen(title))).icon(Icon.FILM).iconColor(Theme.ACCENT)
                    .tip(Component.translatable("justreplay.button.replays.tip")));
        } else if (screen instanceof PauseScreen pause && ((com.vyrriox.justreplay.mixin.PauseScreenAccessor) pause).justreplay$showsPauseMenu()) {
            if (ReplaySession.isRunning()) {
                addWidget.accept(new GlassButton(4, 4, 110, 20, Component.translatable("justreplay.button.editor"),
                        b -> mc.setScreen(new EditorScreen(ReplaySession.current()))).icon(Icon.FILM).iconColor(Theme.ACCENT));
            } else {
                boolean recording = Recorder.isRecording();
                Component label = Component.translatable(recording ? "justreplay.button.stop" : "justreplay.button.record");
                addWidget.accept(new GlassButton(4, 4, 110, 20, label, b -> {
                    toggleRecording();
                    mc.setScreen(null);
                }).icon(recording ? Icon.STOP : Icon.RECORD).iconColor(Theme.RECORD)
                        .tip(Component.translatable("justreplay.button.record.tip", JRKeys.RECORD.getTranslatedKeyMessage())));
                // Instant replay: saves the minutes kept in memory, as the save buffer key does.
                addWidget.accept(new GlassButton(118, 4, 130, 20,
                        Component.translatable("justreplay.button.save_buffer", JRConfig.BUFFER_MINUTES.get()), b -> {
                    mc.setScreen(null);
                    saveBuffer(mc);
                }).icon(Icon.SAVE).iconColor(Theme.ACCENT)
                        .tip(Component.translatable("justreplay.button.save_buffer.tip", JRKeys.SAVE_BUFFER.getTranslatedKeyMessage())));
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
                toast(Recorder.lastStopIncomplete() ? "justreplay.toast.incomplete" : "justreplay.toast.saved", saved.getFileName().toString());
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
            case "justreplay.toast.failed", "justreplay.toast.disk_failed", "justreplay.toast.incomplete" -> Icon.CLOSE;
            default -> Icon.CHECK;
        };
        int accent = switch (key) {
            case "justreplay.toast.started" -> Theme.RECORD;
            case "justreplay.toast.saved" -> Theme.SUCCESS;
            case "justreplay.toast.failed", "justreplay.toast.disk_failed", "justreplay.toast.incomplete",
                 "justreplay.editor.cam.not_path" -> Theme.WARNING;
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
        if (Recorder.recordingFailed()) {
            Path failed = Recorder.stop();
            toast("justreplay.toast.disk_failed", failed == null ? "" : failed.getFileName().toString());
        }
        Recorder recorder = Recorder.active();
        if (recorder != null && recorder.timedRecordingFinished()) {
            Path saved = Recorder.stop();
            if (saved != null) {
                toast(Recorder.lastStopIncomplete() ? "justreplay.toast.incomplete" : "justreplay.toast.next_done", saved.getFileName().toString());
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
