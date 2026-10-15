package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Development helper that records an existing world unattended, for test footage: opens the world named by
 * {@code -Djustreplay.scene}, runs the commands of {@code -Djustreplay.sceneCommands} (separated by {@code ;}) as the
 * player, waits {@code -Djustreplay.sceneDelay} seconds, records for {@code -Djustreplay.sceneSeconds} seconds, then
 * saves the replay and quits the game.
 * <p>
 * With {@code -Djustreplay.sceneReplay} (a folder of the replays folder) it opens that replay instead, waits
 * {@code sceneDelay} seconds, and with {@code -Djustreplay.sceneExport=start-end|name} exports that range at 1080p and
 * 60 fps before quitting. The user's export settings are put back afterwards.
 *
 * @author vyrriox
 */
public final class SceneRecorder {
    private static final String WORLD = System.getProperty("justreplay.scene", "");
    private static final String COMMANDS = System.getProperty("justreplay.sceneCommands", "");
    private static final int DELAY = Integer.getInteger("justreplay.sceneDelay", 5) * 20;
    private static final int LENGTH = Integer.getInteger("justreplay.sceneSeconds", 60) * 20;
    private static final String REPLAY = System.getProperty("justreplay.sceneReplay", "");
    private static final String EXPORT = System.getProperty("justreplay.sceneExport", "");
    /** Replay ticks to seek to and take a screenshot of, separated by commas, before the export. */
    private static final String SHOTS = System.getProperty("justreplay.sceneShots", "");
    private static int shotIndex;

    private static int step;
    private static int wait;
    private static int startTimeout;

    private SceneRecorder() {
    }

    public static boolean enabled() {
        return !WORLD.isEmpty() || !REPLAY.isEmpty();
    }

    private static void log(String message, Object... args) {
        JustReplay.LOGGER.info("SCENE " + message, args);
    }

    public static void tick(Minecraft mc) {
        if (!enabled()) {
            return;
        }
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            mc.setScreen(null);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (!REPLAY.isEmpty()) {
            replay(mc);
            return;
        }
        switch (step) {
            case 0 -> {
                if (mc.level == null && mc.getOverlay() == null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                    log("opening {}", WORLD);
                    mc.createWorldOpenFlows().openWorld(WORLD, () -> log("the world could not be opened"));
                    step = 1;
                }
            }
            case 1 -> {
                // A world with an experimental dimension asks for a backup once: go on without one.
                if (mc.screen instanceof BackupConfirmScreen confirm) {
                    for (var child : confirm.children()) {
                        if (child instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t
                                && t.getKey().equals("selectWorld.backupJoinSkipButton")) {
                            b.onPress();
                        }
                    }
                }
                if (mc.player != null && mc.level != null && mc.screen == null) {
                    IntegratedServer server = mc.getSingleplayerServer();
                    if (server != null && !COMMANDS.isBlank()) {
                        server.execute(() -> {
                            ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
                            if (player == null) {
                                return;
                            }
                            for (String command : COMMANDS.split(";")) {
                                if (!command.isBlank()) {
                                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command.trim());
                                    log("ran /{}", command.trim());
                                }
                            }
                        });
                    }
                    step = 2;
                    wait = DELAY;
                }
            }
            case 2 -> {
                if (Recorder.startManual()) {
                    log("recording for {} s", LENGTH / 20);
                    step = 3;
                    wait = LENGTH;
                } else if (++startTimeout > 200) {
                    log("the recording could not start");
                    step = 4;
                }
            }
            case 3 -> {
                Path dir = Recorder.stop();
                log("recording saved: {}", dir);
                step = 4;
                wait = 40;
            }
            case 4 -> {
                step = 5;
                if (mc.level != null) {
                    mc.level.disconnect();
                }
                mc.disconnect();
                wait = 40;
            }
            case 5 -> {
                step = 6;
                log("done");
                mc.stop();
            }
            default -> {
            }
        }
    }

    private static int savedCrf = -1;
    private static int savedFps;
    private static com.vyrriox.justreplay.export.ExportResolution savedResolution;

    private static void replay(Minecraft mc) {
        switch (step) {
            case 0 -> {
                if (mc.level == null && mc.getOverlay() == null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                    Path dir = com.vyrriox.justreplay.format.ReplayStorage.root().resolve(REPLAY);
                    log("opening replay {}", dir);
                    com.vyrriox.justreplay.replay.ReplaySession.open(dir, mc.screen);
                    step = 1;
                }
            }
            case 1 -> {
                if (com.vyrriox.justreplay.replay.ReplaySession.isRunning() && mc.player != null) {
                    log("replay running");
                    step = 2;
                    wait = DELAY;
                }
            }
            case 2 -> {
                com.vyrriox.justreplay.replay.ReplaySession session = com.vyrriox.justreplay.replay.ReplaySession.current();
                if (net.neoforged.fml.ModList.get().isLoaded("dropthebeam")) {
                    for (var start : com.vyrriox.justreplay.compat.DropTheBeamCompat.songStartTicks()) {
                        log("song start {} \"{}\" at tick {} loop {}", start.turntable().pos(), start.title(), start.tick(), start.loop());
                    }
                }
                if (session != null && !SHOTS.isBlank()) {
                    session.setCameraPath(true);
                    step = 10;
                    return;
                }
                step = 11;
            }
            case 10 -> {
                // Screenshots: seek, let the world settle, grab, next.
                com.vyrriox.justreplay.replay.ReplaySession session = com.vyrriox.justreplay.replay.ReplaySession.current();
                String[] ticks = SHOTS.split(",");
                if (session == null || shotIndex >= ticks.length * 2) {
                    step = 11;
                    return;
                }
                int t = Integer.parseInt(ticks[shotIndex / 2].trim());
                if (shotIndex % 2 == 0) {
                    mc.setScreen(null);
                    session.playback().seek(t);
                    session.playback().setPaused(true);
                    wait = 60;
                } else {
                    net.minecraft.client.Screenshot.grab(mc.gameDirectory, "scene-" + t + ".png", mc.getMainRenderTarget(),
                            c -> log("screenshot at tick {}", t));
                    wait = 10;
                }
                shotIndex++;
            }
            case 11 -> {
                com.vyrriox.justreplay.replay.ReplaySession session = com.vyrriox.justreplay.replay.ReplaySession.current();
                if (EXPORT.isEmpty() || session == null) {
                    step = 4;
                    return;
                }
                String[] parts = EXPORT.split("\\|");
                String[] range = parts[0].split("-");
                mc.setScreen(null);
                session.setCameraPath(true);
                savedCrf = com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.get();
                savedFps = com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.get();
                savedResolution = com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.get();
                com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.set(16);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.set(60);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.set(com.vyrriox.justreplay.export.ExportResolution.FULL_HD_1080);
                var error = com.vyrriox.justreplay.export.VideoExporter.start(session, Integer.parseInt(range[0].trim()),
                        Integer.parseInt(range[1].trim()), parts.length > 1 ? parts[1] : "scene");
                log("export started, error={}", error == null ? "none" : error.getString());
                step = 3;
                wait = 20;
            }
            case 3 -> {
                if (!com.vyrriox.justreplay.export.VideoExporter.isActive()) {
                    log("export finished");
                    if (savedCrf >= 0) {
                        com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.set(savedCrf);
                        com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.set(savedFps);
                        com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.set(savedResolution);
                        com.vyrriox.justreplay.config.JRConfig.SPEC.save();
                    }
                    step = 4;
                    wait = 100;
                }
            }
            case 4 -> {
                step = 5;
                if (mc.level != null) {
                    mc.level.disconnect();
                }
                mc.disconnect();
                wait = 40;
            }
            case 5 -> {
                step = 6;
                log("done");
                mc.stop();
            }
            default -> {
            }
        }
    }
}
