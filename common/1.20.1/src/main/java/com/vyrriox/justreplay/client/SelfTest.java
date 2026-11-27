package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.overlay.Overlay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.mixin.MouseHandlerAccessor;
import com.vyrriox.justreplay.gui.EditorScreen;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Development self test, enabled with {@code -Djustreplay.selftest=true} or a {@code justreplay/selftest.flag} file: records a short session, plays it
 * back, seeks, adds actors and a camera path, exports a clip, then quits. Results go to the log with the
 * {@code SELFTEST} prefix and screenshots to the screenshots folder.
 *
 * @author vyrriox
 */
public final class SelfTest {
    private static final Path FLAG = com.vyrriox.justreplay.platform.Platform.get().gameDir().resolve("justreplay").resolve("selftest.flag");
    private static final boolean ENABLED = Boolean.getBoolean("justreplay.selftest") || Files.exists(FLAG);
    private static final String WORLD = "justreplay-selftest";

    private static int step;

    /** Whether this game runs the self test. */
    public static boolean isEnabled() {
        return ENABLED;
    }
    private static Entity hiddenMob;
    /** The slow motion export of the speed track check. */
    private static VideoExporter slowExport;
    private static int slowWait;
    private static Entity movedMob;
    private static int skippedBefore;
    private static int offsetBefore;
    private static int armorSkippedBefore;
    private static int armorLayerSkippedBefore;
    private static int wait;
    private static int settle;
    private static int backSeekWaits;
    private static int speedStart;
    /** Pause and slow motion: an entity the freeze must hold, and what is read before and after. */
    private static Entity heldEntity;
    private static int heldEntityTicks;
    private static long heldGameTime;
    private static int heldPlayerTicks;
    private static long heldBlockTicks;
    private static long slowStartNanos;
    private static int slowStartCursor;
    private static Path replay;
    /** File the export step writes: its name carries the exported range unless the config keeps the old names. */
    private static Path exportFile;
    /** Start position of the recording, around which the modded test content is placed. */
    private static net.minecraft.core.BlockPos modsBase;
    private static Path clip;
    /** Rename check: the name typed in the naming window, the folder it gives and a replay already in that folder. */
    private static final String RENAME_TYPED = "Self test: clip?";
    private static final String RENAME_FOLDER = "Self test_ clip_";
    private static Path renameDecoy;
    private static String renameDefault;
    private static int renameWait;
    private static java.util.concurrent.CompletableFuture<com.vyrriox.justreplay.format.ReplayBuffer.Saved> clipSave;
    /** Blocks the placement steps seat and lay actors on: stairs and the head of a bed. */
    private static net.minecraft.core.BlockPos stairPos;
    private static net.minecraft.core.BlockPos bedPos;
    private static ReplayProject.ActorData placed;
    private static com.vyrriox.justreplay.editor.ActorPlacement.Spot seatSpot;
    private static int fxStep;
    private static boolean curiosGiven;
    /**
     * More Player Models looks of the recording player: one before the recording (only its snapshot gives it to the
     * actor), one sent to it at {@link #LOOK_CHANGE_TICK}, one at {@link #LOOK_THIRD_TICK} (saved by the mod as the
     * viewer's own). The replay checks read the cursor away from both changes.
     */
    private static final int LOOK_CHANGE_TICK = 100;
    private static final int LOOK_THIRD_TICK = 140;
    private static String lookBefore;
    private static String lookAfter;
    private static String lookThird;
    private static String cameraLook;
    private static int ringWaits;
    private static boolean ringRebuilt;
    private static volatile boolean ringReloaded;
    private static int ringSettle;
    /** Before the recording: 0 the server runs the first commands, 1 the ring, 2 the server again, 3 a second for the client. */
    private static int settlePhase;
    private static boolean modsGiven;
    private static volatile boolean serverAnswered;
    private static boolean serverAsked;
    private static int serverWaits;
    private static int cullSkipped;
    /** Sounds the recording player made itself, heard again while the replay plays (see recordLocalEffect). */
    private static int localBell;
    private static int localAnvil;
    private static int replaySteps;
    private static int recorderSteps;
    /**
     * A recorded packet no client can read: packet id 16383, far past the last packet of the game. Written into the
     * recording, it must come back in the replay's skipped count as "could not be read", the replay going on.
     */
    private static final byte[] UNREADABLE = {(byte) 0xFF, (byte) 0x7F, 0x4A, 0x52, 0x00, 0x2A};
    private static final String UNREADABLE_TYPE = "packet #16383";
    private static boolean unreadableWritten;
    /** Hook calls made on the integration forced off during the replay, how many of them ran, and the cursor then. */
    private static int forcedCalls;
    private static int forcedRan;
    private static int forcedCursor = -1;
    /**
     * Render veil checks of the export: captured frames, frames whose camera was not where the path put the player,
     * the worst gap and the largest step of the path between two frames (the gap check can only fail on a moving
     * path), the mouse turn and the F1 press tried in the middle of the export, and the window capture.
     */
    private static int veilFrames;
    private static int cameraOffFrames;
    private static double cameraWorstOff;
    private static double pathWorstStep;
    private static double pathLastX = Double.NaN;
    private static double pathLastZ;
    private static float pathLastYaw;
    private static double mouseTurned = Double.NaN;
    private static boolean mouseDropped;
    private static Boolean keyHidGui;
    private static boolean veilShot;

    /** Counts, during a replay, the sounds the self test made for its own player while recording. */
    public static void onSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
        if (!ENABLED || !ReplaySession.isRunning()) {
            return;
        }
        String id = sound.getLocation().getPath();
        if (id.equals("block.note_block.bell")) {
            localBell++;
        } else if (id.equals("block.anvil.use")) {
            localAnvil++;
        } else if (id.endsWith(".step")) {
            replaySteps++;
            // Footsteps of the recording player itself: they only exist if the recorder captured them.
            Minecraft mc = Minecraft.getInstance();
            Entity recorder = ReplaySession.current() != null ? ReplaySession.current().findRecorder(mc) : null;
            if (recorder != null && recorder.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) < 2.25) {
                recorderSteps++;
            }
        }
    }
    /**
     * Frame clocks of the mods: playback steps one sample may trail the cursor. A clock shows the time of a frame, one
     * step behind the cursor just after a tick (see CameraController.frameTime), and Iris' was set by the last frame
     * rendered, which can be one step older; the third step is the margin for a slow frame.
     */
    private static final double CLOCK_TOLERANCE = 3.0;
    private static final int SLOW_TICKS = 20;
    /** A step that sits longer than this cannot finish, see {@link #stallLimit}. */
    private static final long STALL_MILLIS = 5 * 60 * 1000L;
    private static int watchedStep = -1;
    private static long watchedSince;
    private static int clockStartStep = 1;
    private static double clockGecko;
    private static double clockIris;
    private static double clockEntity;
    private static int exportClockFirst = -1;
    private static int exportClockLast;
    private static final double[] exportClockGecko = new double[2];
    private static final double[] exportClockIris = new double[2];
    private static int galleryIndex;
    private static String galleryName = "";
    /** Marker renderer counters (level renders, frames with markers, frames an export refused) at a step's start. */
    private static long[] markerCounters = new long[3];
    /** MCP server check: its port, the settings to put back after the run, the HTTP checks running off this thread. */
    private static int mcpPort = -1;
    private static boolean mcpSaved;
    private static boolean mcpWasEnabled;
    private static int mcpOldPort;
    private static String mcpOldToken = "";
    private static java.util.concurrent.CompletableFuture<java.util.List<String>> mcpChecks;
    private static long mcpBadgeBefore;
    private static boolean mcpListeningAtExport;
    private static long mcpBadgeExportStart;
    private static long mcpBadgeExportLast;
    /** Tick the MCP observation checks look at: the recording player walks there, the villager and the actors are around. */
    private static final int MCP_OBSERVE_TICK = 60;
    /** Latest recording tick the stop waits until, for the slow checkpoint to reach the recording's folder. */
    private static final int CHECKPOINT_WAIT_END = 600;
    private static java.util.concurrent.CompletableFuture<com.vyrriox.justreplay.mcp.McpProbe.Observation> mcpObserve;
    /** MCP control checks: the probe driving the buffer clip through MCP only, the markers it must find, a wait count. */
    private static java.util.concurrent.CompletableFuture<com.vyrriox.justreplay.mcp.McpProbe.Steering> mcpSteer;
    private static int mcpSteerMarkers;
    private static int mcpSteerWait;
    /** Tick the control checks go to in the buffer clip, with room for their export of 100 ticks after it. */
    private static final int MCP_STEER_TICK = 100;
    private static int mcpObserveCursor;
    private static net.minecraft.client.gui.screens.Screen mcpObserveScreen;
    private static long mcpGrabsBefore;
    private static long[] markerExportStart = new long[3];
    private static long[] markerExportLast = new long[3];
    /** Export settings and editor mode of the user, saved at the start and put back at the end of the test. */
    private static int savedCrf = -1;
    private static int savedFps;
    private static com.vyrriox.justreplay.export.ExportResolution savedResolution;
    private static com.vyrriox.justreplay.config.JRConfig.EditorMode savedMode;

    private SelfTest() {
    }

    /** RenderUtils.getCurrentTick, NaN without GeckoLib. */
    private static double geckoClock() {
        return readClock("software.bernie.geckolib.util.RenderUtils", "getCurrentTick", null);
    }

    /** Iris' frameTimeCounter in seconds, NaN without Iris. Moves with or without a shader pack. */
    private static double irisClock() {
        try {
            Object timer = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms").getField("TIMER").get(null);
            return readClock("net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", "getFrameTimeCounter", timer);
        } catch (ReflectiveOperationException | LinkageError e) {
            return Double.NaN;
        }
    }

    private static double readClock(String type, String method, Object target) {
        try {
            return ((Number) Class.forName(type).getMethod(method).invoke(target)).doubleValue();
        } catch (ReflectiveOperationException | LinkageError e) {
            return Double.NaN;
        }
    }

    private static void logClock(String name, String mod, double value, String details, boolean ok) {
        if (Double.isNaN(value)) {
            log("clock {} {}: mod absent, skipped", mod, name);
        } else {
            log("clock {} {}: {} {}", mod, name, details, ok ? "OK" : "FAIL");
        }
    }

    private static void logTime(String name, String details, boolean ok) {
        log("time {}: {} {}", name, details, ok ? "OK" : "FAIL");
    }

    /** The level has been paused for a while: it must be frozen and drawn on its last tick. */
    private static void startFreezeCheck(Minecraft mc, LocalPlayer p) {
        logTime("frozen when paused", "frozen " + com.vyrriox.justreplay.replay.TimeControl.isFrozen(), com.vyrriox.justreplay.replay.TimeControl.isFrozen());
        float partial = Frames.partialTick();
        logTime("partial tick when paused", "partial " + partial, partial == 1.0F);
        float ms = com.vyrriox.justreplay.replay.TimeControl.millisPerTick();
        logTime("client ticks at the normal rate when paused", ms + " ms", ms == com.vyrriox.justreplay.replay.TimeControl.DEFAULT_MS_PER_TICK);
        heldEntity = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (com.vyrriox.justreplay.replay.TimeControl.isEntityFrozen(entity) && !entity.isPassenger()) {
                heldEntity = entity;
                heldEntityTicks = entity.tickCount;
                break;
            }
        }
        heldGameTime = mc.level.getGameTime();
        heldPlayerTicks = p.tickCount;
        heldBlockTicks = com.vyrriox.justreplay.replay.TimeControl.heldBlockEntityTicks();
    }

    private static void endFreezeCheck(Minecraft mc, LocalPlayer p) {
        if (heldEntity == null) {
            log("time entity held by the pause: no entity in the level, skipped");
        } else {
            logTime("entity held by the pause", heldEntity.getType() + " " + heldEntityTicks + " -> " + heldEntity.tickCount, heldEntity.tickCount == heldEntityTicks);
        }
        logTime("game time held by the pause", heldGameTime + " -> " + mc.level.getGameTime(), mc.level.getGameTime() == heldGameTime);
        logTime("player ticks through the pause", heldPlayerTicks + " -> " + p.tickCount, p.tickCount > heldPlayerTicks);
        long blockTicks = com.vyrriox.justreplay.replay.TimeControl.heldBlockEntityTicks() - heldBlockTicks;
        if (blockTicks == 0) {
            log("time block entities held by the pause: no ticking block entity in the level, skipped");
        } else {
            logTime("block entities held by the pause", blockTicks + " ticks held", true);
        }
    }

    /** 20 client ticks at half speed last 2 seconds; a replay that ignores the rate takes 1. */
    private static void endSlowCheck(ReplaySession s) {
        long millis = (System.nanoTime() - slowStartNanos) / 1_000_000L;
        float ms = com.vyrriox.justreplay.replay.TimeControl.millisPerTick();
        logTime("slow motion x" + s.playback().speed() + " tick length", ms + " ms", ms == 100.0F);
        logTime("slow motion x" + s.playback().speed() + " duration", SLOW_TICKS + " client ticks in " + millis + " ms", millis >= 1500L);
        int played = s.playback().cursor() - slowStartCursor;
        logTime("slow motion x" + s.playback().speed() + " playback", played + " recorded ticks", played >= SLOW_TICKS - 2 && played <= SLOW_TICKS + 2);
    }

    private static void checkClockHeld(double gecko, double geckoNow, double iris, double irisNow) {
        logClock("paused 40 ticks", "geckolib", geckoNow, gecko + " -> " + geckoNow, gecko == geckoNow);
        logClock("paused 40 ticks", "iris", irisNow, iris + " -> " + irisNow, iris == irisNow);
    }

    private static void checkClockBackSeek(double gecko, double geckoNow, double entity, double entityNow) {
        // 30 ticks were played after the seek back, the first ones may still be lost to the world reload.
        logClock("after seek back", "geckolib", geckoNow, gecko + " -> " + geckoNow, geckoNow - gecko >= 10);
        if (Double.isNaN(geckoNow) || entity < 0 || entityNow < 0) {
            log("clock geckolib entity after seek back: no entity rendered, skipped");
        } else {
            logClock("entity after seek back", "geckolib", entityNow, entity + " -> " + entityNow, entityNow - entity >= 10);
        }
    }

    /**
     * The clocks must rise with the replay cursor, whatever the speed and the render time. A step is worth several
     * replay ticks above x1, so a late last sample takes up to {@link #CLOCK_TOLERANCE} of the last step off the rise
     * and a late first sample adds up to as many of the first step. A clock ignoring the speed still fails: at x4 it
     * rises 10 for 44 ticks, under the 32 this allows.
     */
    private static void checkClockRise(String name, int ticks, int firstStep, int lastStep, double gecko, double geckoNow, double iris, double irisNow) {
        double min = ticks - CLOCK_TOLERANCE * lastStep;
        double max = ticks + CLOCK_TOLERANCE * firstStep;
        double geckoRise = geckoNow - gecko;
        double irisRise = (irisNow - iris) * 20.0;
        logClock(name, "geckolib", geckoNow, "rose " + geckoRise + " for " + ticks + " ticks", geckoRise >= min && geckoRise <= max);
        logClock(name, "iris", irisNow, "rose " + irisRise + " for " + ticks + " ticks", irisRise >= min && irisRise <= max);
    }

    /** First and last client tick of the export once its first frame passed. */
    private static void sampleExportClocks() {
        ReplaySession s = ReplaySession.current();
        if (s == null || !VideoExporter.isActive() || s.playback().cursor() <= 20) {
            return;
        }
        if (exportClockFirst < 0) {
            exportClockFirst = s.playback().cursor();
            exportClockGecko[0] = geckoClock();
            exportClockIris[0] = irisClock();
        }
        exportClockLast = s.playback().cursor();
        exportClockGecko[1] = geckoClock();
        exportClockIris[1] = irisClock();
    }

    private static void log(String message, Object... args) {
        JustReplay.LOGGER.info("SELFTEST " + message, args);
    }

    /** Out of any world, on a menu (vanilla or customised title screen), with loading finished. */
    private static boolean atMenu(Minecraft mc) {
        return mc.level == null && mc.getOverlay() == null && mc.screen != null
                && !(mc.screen instanceof net.minecraft.client.gui.screens.GenericDirtMessageScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ProgressScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
    }

    /**
     * True once the integrated server has run everything sent to it so far: a task queued behind the commands of the
     * self test has come back. A datapack reload holds the server thread for many seconds in a big pack, and the
     * commands sent meanwhile would run after the recording they prepare. Asked again at each call that follows a true
     * answer; gives up after two minutes.
     */
    private static boolean serverCaughtUp(Minecraft mc) {
        net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return true;
        }
        if (!serverAsked) {
            serverAsked = true;
            serverAnswered = false;
            serverWaits = 0;
            server.execute(() -> serverAnswered = true);
            return false;
        }
        serverWaits++;
        if (!serverAnswered && serverWaits < 480) {
            return false;
        }
        if (serverWaits > 12 || !serverAnswered) {
            log("integrated server caught up after {} ticks{}", serverWaits * 5, serverAnswered ? "" : " (no answer, going on)");
        }
        serverAsked = false;
        return true;
    }

    /**
     * Tick the MCP control checks go to: 100, or earlier in a short buffer clip, so that the export of 100 ticks they
     * start after it stays inside the clip.
     */
    private static int steerTick(java.nio.file.Path clip) {
        int duration = ReplayStorage.readMeta(clip).durationTicks;
        return Math.max(1, Math.min(MCP_STEER_TICK, duration - 101));
    }

    private static void next(int delay) {
        step++;
        wait = delay;
    }

    /** Jumps to step {@code target} after {@code delay} ticks. */
    private static void go(int target, int delay) {
        step = target;
        wait = delay;
    }

    private static void saveUserConfig() {
        if (savedCrf >= 0) {
            return;
        }
        savedCrf = com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.get();
        savedFps = com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.get();
        savedResolution = com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.get();
        savedMode = com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.get();
        log("saved user export settings: {} fps, CRF {}, {}", savedFps, savedCrf, savedResolution);
    }

    private static void restoreUserConfig() {
        restoreMcpConfig();
        if (savedCrf < 0) {
            return;
        }
        com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.set(savedCrf);
        com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.set(savedFps);
        com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.set(savedResolution);
        com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(savedMode);
        com.vyrriox.justreplay.config.JRConfig.SPEC.save();
        log("restored user export settings: {} fps, CRF {}, {}", savedFps, savedCrf, savedResolution);
    }

    /** Puts back the MCP settings the run changed: the server goes back off unless the user had it on. */
    private static void restoreMcpConfig() {
        if (!mcpSaved) {
            return;
        }
        mcpSaved = false;
        com.vyrriox.justreplay.config.JRConfig.SPEC.batch(() -> {
            com.vyrriox.justreplay.config.JRConfig.MCP.enabled.set(mcpWasEnabled);
            com.vyrriox.justreplay.config.JRConfig.MCP.port.set(mcpOldPort);
            com.vyrriox.justreplay.config.JRConfig.MCP.token.set(mcpOldToken);
        });
        log("restored the MCP settings: server {}", mcpWasEnabled ? "on" : "off");
    }

    /** A port of 127.0.0.1 nothing listens on right now. */
    private static int freeLocalPort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static boolean localPortOpen(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Ends the run: a failed test quits with its reason in the log, it never leaves the game idle. */
    private static void fail(Minecraft mc, String reason, Throwable cause) {
        JustReplay.LOGGER.error("SELFTEST FAIL at step " + step + ": " + reason, cause);
        step = 100;
        com.vyrriox.justreplay.config.JRConfig.REPLAY_BUFFER.set(false);
        restoreUserConfig();
        try {
            Files.deleteIfExists(FLAG);
        } catch (java.io.IOException e) {
            JustReplay.LOGGER.warn("SELFTEST cannot delete {}", FLAG, e);
        }
        mc.stop();
    }

    /** How long a step may run. Loading the game and an export take long on a slow machine, no other step does. */
    private static long stallLimit() {
        return step == 0 || VideoExporter.isActive() ? 4 * STALL_MILLIS : STALL_MILLIS;
    }

    /** A step waiting for a replay to start can never finish once the session has died: no need to wait the limit. */
    private static void failIfReplayDied(Minecraft mc) {
        if (ReplaySession.current() == null && !ReplaySession.isOpening()) {
            fail(mc, "the replay closed before its playback started", null);
        }
    }

    private static boolean vehiclePlacedLive;
    /** The recording's first steps ran: the walking step may start a tick or two after recording tick 5. */
    private static boolean startPlaced;
    private static int recIndicatorStage;
    private static int recFramesMark;
    private static int recTickMark;
    private static boolean recIndicatorWas;
    private static com.vyrriox.justreplay.gui.ScrubScreen scrub;
    private static int scrubWaits;

    /**
     * Called by the export right after a frame is captured, before the veil goes over it: the rendered camera must be
     * where the camera path put the player this frame, and moving the mouse or pressing F1 must change nothing.
     */
    public static void onExportFrame(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!ENABLED || p == null) {
            return;
        }
        veilFrames++;
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        double off = Math.max(Math.abs(camera.getPosition().x - p.getX()), Math.abs(camera.getPosition().z - p.getZ()));
        double turnOff = Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(camera.getYRot() - p.getYRot())), Math.abs(camera.getXRot() - p.getXRot()));
        // Blocks and degrees: a tenth of a degree is as visible as a thousandth of a block at this range.
        double gap = Math.max(off, turnOff / 100.0);
        if (gap > 1.0E-3) {
            cameraOffFrames++;
        }
        cameraWorstOff = Math.max(cameraWorstOff, gap);
        if (!Double.isNaN(pathLastX)) {
            double step = Math.max(Math.hypot(p.getX() - pathLastX, p.getZ() - pathLastZ),
                    Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot() - pathLastYaw)) / 100.0);
            pathWorstStep = Math.max(pathWorstStep, step);
        }
        pathLastX = p.getX();
        pathLastZ = p.getZ();
        pathLastYaw = p.getYRot();
        long window = mc.getWindow().getWindow();
        if (veilFrames == 12) {
            // A mouse sweep as the window reports it: it must not turn the player the camera rides.
            MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
            float yawBefore = p.getYRot();
            float pitchBefore = p.getXRot();
            int droppedBefore = com.vyrriox.justreplay.export.RenderVeil.droppedMouseLooks();
            mouse.justreplay$setAccumulatedDX(400.0);
            mouse.justreplay$setAccumulatedDY(200.0);
            mouse.justreplay$turnPlayer();
            mouseTurned = Math.max(Math.abs(p.getYRot() - yawBefore), Math.abs(p.getXRot() - pitchBefore));
            mouseDropped = com.vyrriox.justreplay.export.RenderVeil.droppedMouseLooks() > droppedBefore;
        } else if (veilFrames == 14) {
            // F1 shows the HUD again, which would be burnt into the rest of the video.
            mc.keyboardHandler.keyPress(window, 290, 0, 1, 0);
            mc.keyboardHandler.keyPress(window, 290, 0, 0, 0);
        } else if (veilFrames == 24) {
            keyHidGui = mc.options.hideGui;
        }
    }

    /** Window capture with the veil over it, once the export is well into its frames. */
    private static void shotVeil(Minecraft mc) {
        VideoExporter e = VideoExporter.current();
        if (!veilShot && e != null && !e.isPreparing() && e.frames() >= 30) {
            veilShot = true;
            shot(mc, "export-veil");
        }
    }

    /** The veil was over every captured frame, the camera stayed on its path, the mouse and F1 changed nothing. */
    private static void checkVeil() {
        int drawn = com.vyrriox.justreplay.export.RenderVeil.drawnFrames();
        log("render veil drawn on {} frames for {} captured -> {}", drawn, veilFrames, veilFrames > 0 && drawn >= veilFrames ? "OK" : "FAIL");
        log("export camera on its path: {} of {} frames off, worst gap {} (path moved up to {} per frame) -> {}", cameraOffFrames,
                veilFrames, String.format(java.util.Locale.ROOT, "%.5f", cameraWorstOff), String.format(java.util.Locale.ROOT, "%.4f", pathWorstStep),
                veilFrames > 0 && cameraOffFrames == 0 ? "OK" : "FAIL");
        log("export mouse sweep dropped={} turned the camera by {} degrees -> {}", mouseDropped, mouseTurned,
                mouseDropped && mouseTurned == 0.0 ? "OK" : "FAIL");
        log("export F1 press left the HUD hidden={} -> {}", keyHidGui, Boolean.TRUE.equals(keyHidGui) ? "OK" : "FAIL");
        log("export window capture with the veil taken={} -> {}", veilShot, veilShot ? "OK" : "FAIL");
    }

    /** Decodes a picture the MCP tools sent with the game's own image library (stb_image) and keeps it for a look. */
    private static void checkMcpPicture(Minecraft mc, com.vyrriox.justreplay.mcp.McpProbe.Picture picture) {
        int[] size = null;
        double spread = 0.0;
        java.nio.ByteBuffer encoded = org.lwjgl.system.MemoryUtil.memAlloc(picture.data().length);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            encoded.put(picture.data()).flip();
            java.nio.IntBuffer w = stack.mallocInt(1);
            java.nio.IntBuffer h = stack.mallocInt(1);
            java.nio.IntBuffer channels = stack.mallocInt(1);
            java.nio.ByteBuffer pixels = org.lwjgl.stb.STBImage.stbi_load_from_memory(encoded, w, h, channels, 3);
            if (pixels != null) {
                size = new int[]{w.get(0), h.get(0)};
                // A picture with nothing on it (all black, one flat colour) has no spread of brightness.
                double sum = 0.0;
                double squares = 0.0;
                int n = size[0] * size[1];
                for (int i = 0; i < n; i++) {
                    double luma = 0.299 * (pixels.get(i * 3) & 0xFF) + 0.587 * (pixels.get(i * 3 + 1) & 0xFF) + 0.114 * (pixels.get(i * 3 + 2) & 0xFF);
                    sum += luma;
                    squares += luma * luma;
                }
                double mean = sum / n;
                spread = Math.sqrt(Math.max(0.0, squares / n - mean * mean));
                org.lwjgl.stb.STBImage.stbi_image_free(pixels);
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(encoded);
        }
        boolean png = picture.data().length > 0 && (picture.data()[0] & 0xFF) == 0x89;
        String file = "selftest-mcp-" + picture.label().replace(' ', '-') + (png ? ".png" : ".jpg");
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            Files.write(dir.resolve(file), picture.data());
        } catch (java.io.IOException e) {
            log("mcp picture {} could not be saved: {}", file, e.toString());
        }
        boolean right = size != null && size[0] == picture.width() && size[1] == picture.height();
        log("mcp {} decodes with the game's image library at {} (asked {}x{}), brightness spread {}, saved as {} -> {}", picture.label(),
                size == null ? "nothing (" + org.lwjgl.stb.STBImage.stbi_failure_reason() + ")" : size[0] + "x" + size[1], picture.width(),
                picture.height(), String.format(java.util.Locale.ROOT, "%.1f", spread), file, right && spread > 3.0 ? "OK" : "FAIL");
    }

    /** The camera math of the MCP tools against the game's own camera vectors: the look point is the centre, up is up. */
    private static void checkMcpCameraMath(Minecraft mc) {
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        com.vyrriox.justreplay.mcp.McpCamera c = com.vyrriox.justreplay.export.FrameGrab.camera(mc, "current");
        org.joml.Vector3f look = camera.getLookVector();
        org.joml.Vector3f up = camera.getUpVector();
        org.joml.Vector3f left = camera.getLeftVector();
        double[] forward = c.forward();
        double dot = forward[0] * look.x() + forward[1] * look.y() + forward[2] * look.z();
        int w = 640;
        int h = 360;
        com.vyrriox.justreplay.mcp.McpCamera.Projection centre = c.project(c.x() + 10 * look.x(), c.y() + 10 * look.y(), c.z() + 10 * look.z(), w, h);
        com.vyrriox.justreplay.mcp.McpCamera.Projection above = c.project(c.x() + 10 * look.x() + up.x(), c.y() + 10 * look.y() + up.y(),
                c.z() + 10 * look.z() + up.z(), w, h);
        com.vyrriox.justreplay.mcp.McpCamera.Projection toLeft = c.project(c.x() + 10 * look.x() + left.x(), c.y() + 10 * look.y() + left.y(),
                c.z() + 10 * look.z() + left.z(), w, h);
        boolean ok = dot > 0.9999 && Math.abs(centre.screenX() - w / 2.0) < 0.5 && Math.abs(centre.screenY() - h / 2.0) < 0.5
                && above.screenY() < h / 2.0 - 1 && Math.abs(above.screenX() - w / 2.0) < 0.5 && toLeft.screenX() < w / 2.0 - 1
                && Math.abs(toLeft.screenY() - h / 2.0) < 0.5;
        log("mcp camera math matches the game's camera (yaw {}, pitch {}, roll {}): look dot {}, look point at {},{}, up at y {}, left at x {} -> {}",
                String.format(java.util.Locale.ROOT, "%.1f", c.yaw()), String.format(java.util.Locale.ROOT, "%.1f", c.pitch()), c.roll(),
                String.format(java.util.Locale.ROOT, "%.6f", dot), String.format(java.util.Locale.ROOT, "%.2f", centre.screenX()),
                String.format(java.util.Locale.ROOT, "%.2f", centre.screenY()), String.format(java.util.Locale.ROOT, "%.1f", above.screenY()),
                String.format(java.util.Locale.ROOT, "%.1f", toLeft.screenX()), ok ? "OK" : "FAIL");
    }

    /** The REC pill switched off mid recording: no frame draws it and the recording goes on; back on, it shows again. */
    private static void checkRecIndicator(Minecraft mc, int t) {
        var setting = com.vyrriox.justreplay.config.JRConfig.SHOW_RECORDING_INDICATOR;
        if (recIndicatorStage == 0 && t >= 30) {
            recIndicatorStage = 1;
            recIndicatorWas = setting.get();
            setting.set(false);
        } else if (recIndicatorStage == 1 && t >= 32) {
            // Counted from one tick after the switch: the frame being drawn when it flipped may still show it.
            recIndicatorStage = 2;
            recFramesMark = com.vyrriox.justreplay.gui.ReplayHud.recFrames();
            recTickMark = t;
        } else if (recIndicatorStage == 2 && t >= 40) {
            recIndicatorStage = 3;
            int drawn = com.vyrriox.justreplay.gui.ReplayHud.recFrames() - recFramesMark;
            shot(mc, "rec-indicator-hidden");
            log("REC indicator off while recording: drawn on {} frames, recording went on {} ticks -> {}", drawn, t - recTickMark,
                    drawn == 0 && t > recTickMark && Recorder.active() != null ? "OK" : "FAIL");
            setting.set(true);
            recFramesMark = com.vyrriox.justreplay.gui.ReplayHud.recFrames();
        } else if (recIndicatorStage == 3 && t >= 46) {
            recIndicatorStage = 4;
            int drawn = com.vyrriox.justreplay.gui.ReplayHud.recFrames() - recFramesMark;
            shot(mc, "rec-indicator-shown");
            log("REC indicator back on: drawn on {} frames -> {}", drawn, drawn > 0 ? "OK" : "FAIL");
            setting.set(recIndicatorWas);
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "selftest-" + name + ".png", mc.getMainRenderTarget(), c -> log("screenshot {}", name));
    }

    public static void tick(Minecraft mc) {
        if (!ENABLED) {
            return;
        }
        // The test must not stop when the window loses focus.
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            mc.setScreen(null);
        }
        if (step == 100) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        if (step != watchedStep) {
            watchedStep = step;
            watchedSince = now;
        } else if (now - watchedSince > stallLimit()) {
            fail(mc, "stalled for " + (now - watchedSince) / 1000 + " s, screen=" + mc.screen + " level=" + mc.level, null);
            return;
        }
        if (Mods.loaded("geckolib") && ReplaySession.isRunning()) {
            // The GeckoLib clock only counts when it is read, as anything GeckoLib renders does every frame. A scene
            // showing none has no reader, and the two reads of a check would see a single jump back and no rise.
            geckoClock();
        }
        pollServerPack(mc);
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            run(mc);
        } catch (Throwable t) {
            fail(mc, t.toString(), t);
        }
    }

    private static void run(Minecraft mc) throws Exception {
        LocalPlayer p = mc.player;
        switch (step) {
            case 0 -> {
                saveUserConfig();
                if (atMenu(mc)) {
                    checkForcedFailureAtStartup();
                    // Instant replay from the login on, with a short window so the clip starts at a buffer checkpoint.
                    System.setProperty("justreplay.bufferWindowTicks", "160");
                    com.vyrriox.justreplay.config.JRConfig.REPLAY_BUFFER.set(true);
                    LevelStorageSource source = mc.getLevelSource();
                    if (source.levelExists(WORLD)) {
                        try (var access = source.createAccess(WORLD)) {
                            access.deleteLevel();
                        }
                    }
                    GameRules rules = new GameRules();
                    rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                    LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                            WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(42L, false, false),
                            access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value()
                                    .createWorldDimensions());
                    log("world requested");
                    next(20);
                }
            }
            case 1 -> {
                if (p != null && mc.level != null) {
                    // Mods may open welcome screens on join; give the world time to settle, then close them.
                    if (mc.screen != null && ++settle > 200) {
                        log("closing screen {}", mc.screen.getClass().getName());
                        mc.setScreen(null);
                    }
                    if (mc.screen == null) {
                        log("in world at {}", p.position());
                        pushServerPack(mc);
                        Mods.placeVehicle(mc, p, 8, 6);
                        Mods.seedRing(mc);
                        // Worn well before the recording starts: only its snapshot can give it to the actor.
                        Mods.setLook(p, "0.8");
                        next(60);
                    }
                }
            }
            case 2 -> {
                // Everything sent so far must have run on the server and reached the client before the recording.
                if (settlePhase == 0 || settlePhase == 2) {
                    if (!serverCaughtUp(mc)) {
                        wait = 5;
                        return;
                    }
                    settlePhase++;
                }
                if (settlePhase == 1) {
                    if (Mods.ringPending(mc)) {
                        wait = 5;
                        return;
                    }
                    settlePhase = 2;
                    wait = 5;
                    return;
                }
                if (settlePhase == 3 && !modsGiven) {
                    // Entities and worn items of mods with no integration of their own, put there before the recording:
                    // only its snapshot has them. Once the server ran the reloads of the ring: a reload still under
                    // way syncs the Curios of the player again and empties them on the client. Then the server again.
                    modsGiven = true;
                    SelfTestModState.prepare(p);
                    Mods.giveRing(mc);
                    settlePhase = 2;
                    wait = 5;
                    return;
                }
                if (settlePhase == 3) {
                    settlePhase = 4;
                    wait = 20;
                    return;
                }
                p.connection.sendCommand("summon minecraft:pig ~4 ~ ~2");
                p.connection.sendCommand("summon minecraft:villager ~-3 ~ ~3");
                p.connection.sendCommand("setblock ~2 ~ ~ minecraft:gold_block");
                // Modded content when available: an animated GeckoLib mob.
                p.connection.sendCommand("summon mowziesmobs:foliaath ~-4 ~ ~-3");
                // Furniture for the placement steps, out of the walking circle: stairs facing west, a bed facing south.
                net.minecraft.core.BlockPos base = p.blockPosition();
                modsBase = base;
                Mods.prepare(mc);
                stairPos = base.offset(9, 0, 0);
                bedPos = base.offset(9, 0, -3);
                p.connection.sendCommand(String.format("setblock %d %d %d minecraft:oak_stairs[facing=west]", stairPos.getX(), stairPos.getY(), stairPos.getZ()));
                p.connection.sendCommand(String.format("setblock %d %d %d minecraft:red_bed[facing=south,part=head]", bedPos.getX(), bedPos.getY(), bedPos.getZ()));
                p.connection.sendCommand(String.format("setblock %d %d %d minecraft:red_bed[facing=south,part=foot]", bedPos.getX(), bedPos.getY(), bedPos.getZ() - 1));
                boolean started = Recorder.startManual();
                log("record started: {}", started);
                next(5);
            }
            case 3 -> {
                // Walk in a circle for ~10 seconds, jumping and swinging.
                int t = Recorder.active() != null ? Recorder.active().tick() : 0;
                checkRecIndicator(mc, t);
                if (t >= 5 && !startPlaced) {
                    // Placed once the recording runs: a block changed in the same tick as the start could be missed.
                    startPlaced = true;
                    Mods.place(mc, p, modsBase);
                    lookBefore = Mods.look(p);
                    SelfTestModState.captureStart(mc);
                }
                if (t == LOOK_CHANGE_TICK) {
                    // The server answers the recording player under its own UUID: the recording must name its actor.
                    Mods.setLook(p, "1.2");
                }
                if (t == LOOK_THIRD_TICK - 10) {
                    lookAfter = Mods.look(p);
                }
                if (t == LOOK_THIRD_TICK) {
                    // A third look, saved by the mod as the viewer's own: the camera must keep it through the replay.
                    Mods.setLook(p, "0.9");
                }
                if (t == LOOK_THIRD_TICK + 50) {
                    lookThird = Mods.look(p);
                    Mods.checkRecordingLook();
                }
                if (t >= 10 && !vehiclePlacedLive) {
                    // A second car placed during the recording: its data comes from the recorded server answer.
                    vehiclePlacedLive = true;
                    Mods.placeVehicle(mc, p, -8, -8);
                }
                p.setYRot(p.getYRot() + 4);
                p.setYHeadRot(p.getYRot());
                Vec3 look = Vec3.directionFromRotation(0, p.getYRot()).scale(0.25);
                p.setDeltaMovement(look.x, p.getDeltaMovement().y, look.z);
                if (t % 40 == 0 && p.onGround()) {
                    p.jumpFromGround();
                }
                if (t % 15 == 0) {
                    p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                if (t == 30) {
                    // Effects the client plays for its own player (as for its footsteps and the blocks it places):
                    // the server never sends them back, the recording must capture them itself.
                    mc.level.playSeededSound(p, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL,
                            net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F, 0L);
                    mc.level.levelEvent(p, 1030, p.blockPosition(), 0);
                    log("local effects played at recording tick 30: a bell sound and an anvil level event");
                }
                if (t >= 70 && !unreadableWritten) {
                    // A packet no client can read, as one of a mod that changed since would come back.
                    unreadableWritten = Recorder.recordRawForSelfTest(UNREADABLE);
                    log("unreadable packet ({}) written into the recording at tick {} -> {}", UNREADABLE_TYPE, t,
                            unreadableWritten ? "OK" : "FAIL");
                }
                if (t == 100) {
                    p.connection.sendCommand("setblock ~ ~3 ~ minecraft:diamond_block");
                    Mods.checkRecordingCurios(p);
                }
                if ((t == 60 || t == 150) && mc.screen == null) {
                    // Through the key, as a player would: two markers 90 ticks apart, both inside the buffer clip.
                    net.minecraft.client.KeyMapping.click(((com.vyrriox.justreplay.mixin.KeyMappingAccessor) JRKeys.RECORDING_MARKER).justreplay$getKey());
                    log("recording marker key pressed at recording tick {}", t);
                }
                if (t % 40 == 0 && Recorder.capturing() != null) {
                    log("recording cost: {}", Recorder.capturing().lastCost());
                }
                if (t == 10 && Recorder.capturing() != null) {
                    // Built under forced slow frames and kept by the recording: the checkpoint seek below restores it.
                    Recorder.capturing().startSlowCheckpointTest();
                }
                if (t >= 200 && clipSave == null) {
                    clipSave = Recorder.saveBuffer();
                    log("buffer save requested: {}", clipSave != null);
                    if (clipSave == null) {
                        clipSave = java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("no buffer"));
                    }
                }
                if (t >= 200 && clipSave.isDone() && (checkpointWritten() || t >= CHECKPOINT_WAIT_END)) {
                    verifyClip(mc);
                    int slowTicks = Recorder.capturing() != null ? Recorder.capturing().slowCheckpointTicks() : -1;
                    log("checkpoint under forced slow frames: {} ({} ticks, recording stopped at tick {})",
                            slowTicks >= 0 ? "PASS" : "FAIL", slowTicks, t);
                    replay = Recorder.stop();
                    int[] checkpoints = com.vyrriox.justreplay.format.Checkpoints.list(replay);
                    log("record stopped: {} packets={} stream={} bytes checkpoints={} bytes in {} files", replay,
                            ReplayStorage.readStream(replay).size(), Files.size(replay.resolve(ReplayStorage.STREAM)),
                            folderSize(replay.resolve(com.vyrriox.justreplay.format.Checkpoints.FOLDER)), checkpoints.length);
                    log("recording checkpoints at ticks {} -> {}", java.util.Arrays.toString(checkpoints),
                            checkpoints.length > 0 ? "OK" : "FAIL");
                    mc.level.disconnect();
                    mc.clearLevel(new TitleScreen());
                    next(40);
                }
            }
            case 4 -> {
                if (atMenu(mc)) {
                    Mods.checkCulling("before the replay", true);
                    openThroughModWarning(mc, replay);
                    log("replay opening");
                    next(40);
                }
            }
            case 5 -> {
                if (ReplaySession.isRunning() && p != null) {
                    next(60);
                } else {
                    failIfReplayDied(mc);
                }
            }
            case 6 -> {
                ReplaySession s = ReplaySession.current();
                checkRecordingMarkers(s);
                logWorld(mc, s, "playing");
                log("mods while playing: {}", Mods.state(mc, s));
                Mods.checkCurios(mc, s, "at start");
                SelfTestSkin.check(mc, s, "at start");
                SelfTestSkin.checkRepair(mc, s);
                SelfTestModState.check(mc, s, "at start");
                cameraLook = Mods.look(mc.player);
                Mods.checkLook(mc, s, "at start");
                Mods.checkLookGuards(mc);
                Mods.checkJade("at start");
                Mods.checkVehicles(mc, "at start");
                Mods.checkCulling("during the replay", false);
                cullSkipped = Math.max(0, com.vyrriox.justreplay.compat.EntityCullingCompat.skippedEntityTicks());
                shot(mc, "1-playing");
                // Mod clocks after a seek into the emote, then while paused: nothing may move.
                s.playback().seek(60);
                s.playback().setPaused(true);
                log("mods after seek to 60, paused: {}", Mods.state(mc, s));
                next(20);
            }
            case 7 -> {
                ReplaySession s = ReplaySession.current();
                log("mods 20 ticks later, still paused: {}", Mods.state(mc, s));
                Mods.checkLook(mc, s, "after seek to 60");
                shot(mc, "1b-paused60");
                startFreezeCheck(mc, p);
                clockGecko = geckoClock();
                clockIris = irisClock();
                SelfTestModState.hideChest(mc, s);
                go(60, 40);
            }
            case 60 -> {
                if (SelfTestModState.waitingForFrames(mc)) {
                    wait = 5;
                    return;
                }
                SelfTestModState.checkHiddenChest(mc, ReplaySession.current(), () -> shot(mc, "1c-chest-hidden"));
                // 40 more ticks paused: the clocks read while rendering must not have moved.
                checkClockHeld(clockGecko, geckoClock(), clockIris, irisClock());
                endFreezeCheck(mc, p);
                ReplaySession s = ReplaySession.current();
                s.playback().setPaused(false);
                // Half speed (the index of x0.5 in Playback.SPEEDS).
                s.playback().setSpeedIndex(2);
                slowStartNanos = System.nanoTime();
                slowStartCursor = s.playback().cursor();
                go(62, SLOW_TICKS);
            }
            case 62 -> {
                ReplaySession s = ReplaySession.current();
                endSlowCheck(s);
                s.playback().resetSpeed();
                if (Mods.loaded("entityculling")) {
                    // The mod's toggle key, simulated: the next ticks must switch it off again.
                    com.vyrriox.justreplay.compat.EntityCullingCompat.setEnabled(true);
                }
                // The scrub key frees the mouse over the timeline, out of the editor: a click there moves the play head.
                scrub = new com.vyrriox.justreplay.gui.ScrubScreen(s);
                mc.setScreen(scrub);
                scrubWaits = 0;
                go(112, 2);
            }
            case 112 -> {
                ReplaySession s = ReplaySession.current();
                int w = com.vyrriox.justreplay.gui.ReplayHud.barWidth();
                if (w <= 0 && ++scrubWaits < 100) {
                    wait = 2;
                    return;
                }
                int duration = s.playback().duration();
                int from = s.playback().cursor();
                int x = com.vyrriox.justreplay.gui.Timeline.xOf(150, com.vyrriox.justreplay.gui.ReplayHud.barX(), w, duration);
                scrub.seekAt(x);
                int now = s.playback().cursor();
                double pixel = (double) duration / Math.max(1, w - 1);
                boolean open = mc.screen == scrub;
                log("scrub: timeline under a free mouse (screen open={}, bar {} px), click at x={} moved the play head {} -> {} (target 150, one pixel = {} ticks) -> {}",
                        open, w, x, from, now, String.format(java.util.Locale.ROOT, "%.1f", pixel),
                        open && w > 0 && Math.abs(now - 150) <= pixel + 1 ? "OK" : "FAIL");
                go(113, 3);
            }
            case 113 -> {
                shot(mc, "1d-scrub");
                mc.setScreen(null);
                // The checks after the forward seek read the world at tick 150 exactly.
                ReplaySession.current().playback().seek(150);
                go(61, 3);
            }
            case 61 -> {
                // Entity Culling's tickEntity mixin skips an entity only while its switch is on. The simulated key
                // press above leaves it on until the replay's own end-of-tick sync, so entities ticked in that
                // single tick can be skipped (seen once in three runs). That window is the test's own doing:
                // count from here, once the switch has been forced off again.
                cullSkipped = Math.max(0, com.vyrriox.justreplay.compat.EntityCullingCompat.skippedEntityTicks());
                float ms = com.vyrriox.justreplay.replay.TimeControl.millisPerTick();
                logTime("normal speed restored", ms + " ms", ms == com.vyrriox.justreplay.replay.TimeControl.DEFAULT_MS_PER_TICK);
                go(8, 27);
            }
            case 8 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "after forward seek");
                log("mods after forward seek: {}", Mods.state(mc, s));
                Mods.checkCurios(mc, s, "after forward seek");
                SelfTestSkin.check(mc, s, "after forward seek");
                SelfTestModState.check(mc, s, "after forward seek");
                Mods.checkLook(mc, s, "after forward seek");
                Mods.checkCulling("after a toggle key press", false);
                Mods.checkCullingEntities(mc);
                checkUnreadableCounted(s);
                fireForcedFailureHooks(s);
                shot(mc, "2-seek150");
                clockGecko = geckoClock();
                clockEntity = com.vyrriox.justreplay.compat.ReplayCompat.lastGeckoEntityFrameTime();
                SelfTestModState.clearTrinketSlots();
                s.playback().seek(20);
                next(30);
            }
            case 9 -> {
                ReplaySession s = ReplaySession.current();
                // In a big pack a resource reload holds the game for a while after the jump back: the checks below
                // expect the replay playing on from tick 20, so wait for it to move (two minutes at most).
                if (s != null && s.playback().cursor() <= 20 && ++backSeekWaits < 480) {
                    wait = 5;
                    return;
                }
                logWorld(mc, s, "after backward seek");
                Mods.checkCurios(mc, s, "after backward seek");
                SelfTestSkin.check(mc, s, "after backward seek");
                SelfTestModState.checkTrinketSlots();
                SelfTestModState.check(mc, s, "after backward seek");
                Mods.checkLook(mc, s, "after backward seek");
                Mods.checkJade("after backward seek");
                Mods.checkVehicles(mc, "after backward seek");
                checkForcedFailureDuringReplay(s);
                shot(mc, "3-seek20");
                Mods.faceVehicle(mc);
                checkClockBackSeek(clockGecko, geckoClock(), clockEntity, com.vyrriox.justreplay.compat.ReplayCompat.lastGeckoEntityFrameTime());
                clockGecko = geckoClock();
                clockIris = irisClock();
                clockStartStep = Math.max(1, s.playback().lastStep());
                speedStart = s.playback().cursor();
                s.playback().changeSpeed(2);
                next(10);
            }
            case 10 -> {
                ReplaySession s = ReplaySession.current();
                if (Mods.loaded("mts")) {
                    shot(mc, "3b-vehicle");
                }
                log("speed x{}: cursor advanced {} ticks in 10 client ticks", s.playback().speed(), s.playback().cursor() - speedStart);
                checkClockRise("speed", s.playback().cursor() - speedStart, clockStartStep, Math.max(1, s.playback().lastStep()),
                        clockGecko, geckoClock(), clockIris, irisClock());
                s.playback().resetSpeed();
                s.playback().seek(190);
                s.playback().seek(130);
                int restored = s.playback().lastSeekCheckpoint();
                log("checkpoint seek 190 -> 130 from {} -> {}", restored >= 0 ? "the checkpoint at " + restored : "the start",
                        restored >= 0 ? "OK" : "FAIL");
                logWorld(mc, s, "after checkpoint seek to 130");
                s.playback().seek(20);
                s.jumpToRecorder(mc);
                Vec3 c = p.position();
                ReplayProject.ActorData player = new ReplayProject.ActorData();
                player.name = "Notch";
                player.mainHand = "minecraft:diamond_sword";
                // Hidden helmet, visible chestplate: checked once the actors are drawn.
                player.head = "minecraft:diamond_helmet";
                player.chest = "minecraft:iron_chestplate";
                player.hiddenArmor = com.vyrriox.justreplay.editor.ArmorSlots.HEAD;
                armorSkippedBefore = com.vyrriox.justreplay.editor.ArmorSlots.skipped();
                armorLayerSkippedBefore = com.vyrriox.justreplay.editor.ArmorSlots.armorLayerSkipped();
                player.keyframes.add(new ReplayProject.ActorKeyframe(20, c.x + 2, c.y, c.z + 2, 90, 0));
                player.keyframes.add(new ReplayProject.ActorKeyframe(120, c.x + 8, c.y, c.z + 2, 90, 0));
                ReplayProject.ActorData zombie = new ReplayProject.ActorData();
                zombie.type = "minecraft:zombie";
                zombie.name = "Bob";
                zombie.keyframes.add(new ReplayProject.ActorKeyframe(20, c.x - 2, c.y, c.z + 2, 0, 0));
                s.project().actors.add(player);
                s.project().actors.add(zombie);
                ReplayProject.ActorData modded = new ReplayProject.ActorData();
                modded.type = "mowziesmobs:foliaath";
                modded.keyframes.add(new ReplayProject.ActorKeyframe(20, c.x, c.y, c.z + 4, 180, 0));
                s.project().actors.add(modded);
                // Animations and a Blockbench model actor.
                zombie.actions.add(new ReplayProject.ActorAction(30, com.vyrriox.justreplay.editor.ActionType.AGGRESSIVE, 60, ""));
                zombie.actions.add(new ReplayProject.ActorAction(50, com.vyrriox.justreplay.editor.ActionType.HURT, 0, ""));
                player.actions.add(new ReplayProject.ActorAction(25, com.vyrriox.justreplay.editor.ActionType.WAVE, 40, ""));
                // A custom emote from the emotes folder, no other mod needed.
                com.vyrriox.justreplay.editor.EmoteLibrary.Emote wave = com.vyrriox.justreplay.editor.EmoteLibrary.get("example_emotes:wave");
                if (wave != null) {
                    zombie.actions.add(new ReplayProject.ActorAction(90, com.vyrriox.justreplay.editor.ActionType.CUSTOM_EMOTE, 40, "example_emotes:wave"));
                    double[][] pose = com.vyrriox.justreplay.editor.EmoteLibrary.sample(wave, 5.0);
                    log("custom emote {} right arm zRot at tick 5 = {} rad ({} emotes listed)", wave.id,
                            String.format(java.util.Locale.ROOT, "%.3f", pose[com.vyrriox.justreplay.editor.EmoteLibrary.Bone.RIGHT_ARM.ordinal()][2]),
                            com.vyrriox.justreplay.editor.EmoteLibrary.list().size());
                }
                // Walk from the keyframe to a point 6 blocks away, and the zombie follows the recording player.
                player.actions.add(new ReplayProject.ActorAction(30, com.vyrriox.justreplay.editor.ActionType.WALK_TO, 0,
                        String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", c.x + 2, c.y, c.z + 8)));
                zombie.actions.add(new ReplayProject.ActorAction(60, com.vyrriox.justreplay.editor.ActionType.FOLLOW, 0, "recorder|2"));
                ReplayProject.ActorData model = new ReplayProject.ActorData();
                model.type = ReplayProject.ActorData.MODEL;
                model.model = "example.bbmodel";
                model.modelAnimation = "walk";
                model.keyframes.add(new ReplayProject.ActorKeyframe(20, c.x + 4, c.y, c.z + 4, 180, 0));
                s.project().actors.add(model);
                s.project().selected().keyframes.add(new ReplayProject.CameraKeyframe(20, c.x - 6, c.y + 3, c.z - 6, -45, 20, 0, 70));
                s.project().selected().keyframes.add(new ReplayProject.CameraKeyframe(80, c.x + 6, c.y + 4, c.z - 6, 45, 25, 10, 60));
                s.project().selected().keyframes.add(new ReplayProject.CameraKeyframe(140, c.x + 6, c.y + 2, c.z + 8, 160, 10, 0, 70));
                s.saveProject();
                s.setCameraPath(true);
                s.playback().setPaused(false);
                next(60);
            }
            case 11 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "camera path + actors");
                for (ReplayProject.ActorData a : s.project().actors) {
                    Entity e = s.actors().entityOf(a);
                    log("actor {} -> {}", a.type, e == null ? "missing" : e.getClass().getSimpleName() + " @ " + e.position());
                }
                shot(mc, "4-path-actors");
                checkHiddenArmor(mc, s);
                // Every curve and timing must give a usable camera state; the free camera then shows the 3D path.
                for (String curve : new String[]{"CATMULL_ROM", "BEZIER", "LINEAR"}) {
                    for (String timing : new String[]{"CONSTANT_SPEED", "KEYFRAMES"}) {
                        s.project().selected().curve = curve;
                        s.project().selected().timing = timing;
                        double[] a = com.vyrriox.justreplay.editor.CameraPath.of(s.project()).sample(50);
                        log("path {} {} at t=50: {} {} {}", curve, timing, a[0], a[1], a[2]);
                    }
                }
                s.project().selected().curve = "BEZIER";
                s.project().selected().timing = "CONSTANT_SPEED";
                s.setCameraPath(false);
                net.minecraft.world.entity.Entity recorder = s.findRecorder(mc);
                // Multi-camera: a static and a follow camera, cut track path -> static (blended) -> follow.
                ReplayProject.Camera path = s.project().selected();
                ReplayProject.Camera fixed = new ReplayProject.Camera("Wide", ReplayProject.Camera.STATIC);
                ReplayProject.CameraKeyframe first = path.keyframes.get(0);
                fixed.x = first.x + 6;
                fixed.y = first.y + 7;
                fixed.z = first.z - 4;
                fixed.pitch = 40;
                s.project().cameras.add(fixed);
                ReplayProject.Camera follow = new ReplayProject.Camera("Follow", ReplayProject.Camera.FOLLOW);
                if (recorder != null) {
                    follow.target = recorder.getUUID().toString();
                }
                s.project().cameras.add(follow);
                s.project().cutTo(20, path, 0);
                s.project().cutTo(45, fixed, 10);
                s.project().cutTo(65, follow, 0);
                s.project().cutsEnd = 80;
                for (double t : new double[]{30, 50, 60, 70}) {
                    double[] a = com.vyrriox.justreplay.editor.CameraRig.sampleProject(s.project(), t, 0.0F, false);
                    log("cuts at t={}: {}", t, a == null ? "none" : a[0] + " " + a[1] + " " + a[2] + " yaw " + a[3]);
                }
                int[] range = s.project().exportRange(s.playback().duration());
                log("cut track: {} cuts, export range {}..{}", s.project().cuts.size(), range[0], range[1]);
                s.saveProject();
                if (recorder != null) {
                    s.spectate().start(mc, recorder, true);
                }
                log("spectating recorder: {} camera entity={}", s.spectate().isActive(),
                        mc.getCameraEntity() == null ? "none" : mc.getCameraEntity().getClass().getSimpleName());
                s.spectate().stop(mc);
                com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(com.vyrriox.justreplay.config.JRConfig.EditorMode.ADVANCED);
                addBeepClip(s);
                mc.setScreen(new EditorScreen(s));
                next(10);
            }
            case 12 -> {
                checkRegistryCompletion(ReplaySession.current());
                log("replay version check: {}", com.vyrriox.justreplay.replay.VersionCheck.selfTest() ? "OK" : "FAIL");
                shot(mc, "5-editor");
                // The simple editor on the same project.
                com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(com.vyrriox.justreplay.config.JRConfig.EditorMode.SIMPLE);
                mc.setScreen(new EditorScreen(ReplaySession.current()));
                go(30, 10);
            }
            case 30 -> {
                shot(mc, "5b-editor-simple");
                // Interactive placement: a new actor follows the crosshair, then the click is simulated.
                ReplaySession s = ReplaySession.current();
                s.playback().setPaused(true);
                s.setCameraPath(false);
                com.vyrriox.justreplay.replay.Playback.placeCamera(p, new Vec3(stairPos.getX() - 5.5, stairPos.getY() + 3.0, stairPos.getZ() + 0.5), -90.0F, 40.0F);
                placed = new ReplayProject.ActorData();
                placed.type = "minecraft:villager";
                placed.name = "Placed";
                s.project().actors.add(placed);
                com.vyrriox.justreplay.gui.PlacementTool.start((EditorScreen) mc.screen, s, placed,
                        com.vyrriox.justreplay.editor.ActorPlacement.Kind.PLACE, true);
                log("placement started: active={} screen={}", com.vyrriox.justreplay.gui.PlacementTool.isActive(), mc.screen);
                next(15);
            }
            case 31 -> {
                ReplaySession s = ReplaySession.current();
                var tool = com.vyrriox.justreplay.gui.PlacementTool.active();
                Entity ghost = s.actors().entityOf(placed);
                log("placement preview: spot={} ghost={}", tool == null ? "none" : tool.spot(), ghost == null ? "missing" : ghost.position());
                shot(mc, "7-placement");
                next(2);
            }
            case 32 -> {
                ReplaySession s = ReplaySession.current();
                var tool = com.vyrriox.justreplay.gui.PlacementTool.active();
                if (tool != null) {
                    tool.confirm();
                }
                log("placement confirmed: keyframes={} first={} screen={} selected={}", placed.keyframes.size(),
                        placed.keyframes.isEmpty() ? "none" : placed.keyframes.get(0).x + " " + placed.keyframes.get(0).y + " " + placed.keyframes.get(0).z
                                + " yaw " + placed.keyframes.get(0).yaw,
                        mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(),
                        mc.screen instanceof EditorScreen e && e.selectedActor() != null ? e.selectedActor().name : "none");
                // Seat one actor on the stairs and lay another in the bed with the placement API.
                ReplayProject.ActorData sitter = new ReplayProject.ActorData();
                sitter.name = "Notch";
                ReplayProject.ActorData sleeper = new ReplayProject.ActorData();
                sleeper.name = "jeb_";
                s.project().actors.add(sitter);
                s.project().actors.add(sleeper);
                // The sitter walked and followed someone before sitting down: the seat must still be exact.
                int sitTick = s.playback().cursor();
                sitter.keyframes.add(new ReplayProject.ActorKeyframe(Math.max(0, sitTick - 30), stairPos.getX() + 5.5, stairPos.getY(),
                        stairPos.getZ() + 5.5, 0, 0));
                sitter.actions.add(new ReplayProject.ActorAction(Math.max(0, sitTick - 25), com.vyrriox.justreplay.editor.ActionType.WALK_TO, 0,
                        String.format(java.util.Locale.ROOT, "%.2f %d %.2f", stairPos.getX() + 9.5, stairPos.getY(), stairPos.getZ() + 0.5)));
                sitter.actions.add(new ReplayProject.ActorAction(Math.max(0, sitTick - 10), com.vyrriox.justreplay.editor.ActionType.FOLLOW, 0, "recorder|2"));
                sitter.sort();
                var stairHit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(stairPos).add(0, 0.5, 0), net.minecraft.core.Direction.UP, stairPos, false);
                var bedHit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(bedPos).add(0, 0.06, 0), net.minecraft.core.Direction.UP, bedPos, false);
                var seat = com.vyrriox.justreplay.gui.PlacementTool.placeAt(s, sitter, com.vyrriox.justreplay.editor.ActorPlacement.Kind.SIT, stairHit, 0.0F);
                seatSpot = seat;
                var bed = com.vyrriox.justreplay.gui.PlacementTool.placeAt(s, sleeper, com.vyrriox.justreplay.editor.ActorPlacement.Kind.BED, bedHit, 0.0F);
                log("seat on {}: {} actions={}", mc.level.getBlockState(stairPos), seat, sitter.actions.size());
                log("bed at {}: {} actions={}", mc.level.getBlockState(bedPos), bed, sleeper.actions.size());
                mc.setScreen(null);
                com.vyrriox.justreplay.replay.Playback.placeCamera(p, new Vec3(stairPos.getX() + 4.5, stairPos.getY() + 2.0, stairPos.getZ() + 2.0), 125.0F, 22.0F);
                next(40);
            }
            case 33 -> {
                ReplaySession s = ReplaySession.current();
                for (ReplayProject.ActorData a : s.project().actors) {
                    Entity e = s.actors().entityOf(a);
                    if ("Notch".equals(a.name) && e != null && seatSpot != null
                            && a.actions.stream().anyMatch(x -> x.actionType() == com.vyrriox.justreplay.editor.ActionType.FOLLOW)) {
                        double err = Math.max(Math.hypot(e.getX() - seatSpot.x(), e.getZ() - seatSpot.z()), Math.abs(e.getY() + 0.625 - seatSpot.y()));
                        log("seat after walk and follow: error {} blocks, pose {} -> {}", String.format(java.util.Locale.ROOT, "%.3f", err),
                                e.getPose(), err < 0.05 && e.getPose() == net.minecraft.world.entity.Pose.SITTING ? "OK" : "FAIL");
                    }
                    if (a == placed || "Notch".equals(a.name) || "jeb_".equals(a.name)) {
                        log("placed actor {} -> {}", a.name, e == null ? "missing" : e.getPose() + " @ " + e.position() + " yaw " + e.getYRot()
                                + (e instanceof net.minecraft.world.entity.LivingEntity l ? " body " + l.yBodyRot + " bed " + l.getBedOrientation() : ""));
                    }
                }
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.LivingEntity l && l.getPose() == net.minecraft.world.entity.Pose.SLEEPING) {
                        log("sleeper {} body {} bed {} @ {}", e.getClass().getSimpleName(), l.yBodyRot, l.getBedOrientation(), e.position());
                    }
                }
                shot(mc, "8-seat-bed");
                // Undo / redo of the last editor change (the bed placement added an actor).
                ReplaySession us = ReplaySession.current();
                us.saveProject();
                int before = us.project().actors.size();
                boolean undone = us.undo();
                int afterUndo = us.project().actors.size();
                boolean redone = us.redo();
                log("undo/redo: actors {} -> {} -> {} (undo={} redo={})", before, afterUndo, us.project().actors.size(), undone, redone);
                galleryIndex = 0;
                go(40, 5);
            }
            case 40 -> {
                // Gallery of every editor tab in both modes, the help overlay and the other screens.
                ReplaySession gs = ReplaySession.current();
                java.util.List<String> simpleTabs;
                com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(com.vyrriox.justreplay.config.JRConfig.EditorMode.SIMPLE);
                simpleTabs = EditorScreen.tabIds();
                com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(com.vyrriox.justreplay.config.JRConfig.EditorMode.ADVANCED);
                java.util.List<String> advancedTabs = EditorScreen.tabIds();
                int total = simpleTabs.size() + advancedTabs.size();
                if (galleryIndex < total) {
                    boolean simpleMode = galleryIndex < simpleTabs.size();
                    String id = simpleMode ? simpleTabs.get(galleryIndex) : advancedTabs.get(galleryIndex - simpleTabs.size());
                    com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(simpleMode
                            ? com.vyrriox.justreplay.config.JRConfig.EditorMode.SIMPLE : com.vyrriox.justreplay.config.JRConfig.EditorMode.ADVANCED);
                    EditorScreen editor = new EditorScreen(gs);
                    mc.setScreen(editor);
                    editor.showTabForTest(id);
                    galleryName = "ui-" + (simpleMode ? "simple-" : "advanced-") + id;
                    go(41, 8);
                } else if (galleryIndex == total) {
                    EditorScreen editor = new EditorScreen(gs);
                    mc.setScreen(editor);
                    editor.setHelpForTest(true);
                    galleryName = "ui-help";
                    go(41, 8);
                } else if (galleryIndex == total + 1) {
                    mc.setScreen(new com.vyrriox.justreplay.gui.ExportScreen(null, gs));
                    galleryName = "ui-export";
                    go(41, 8);
                } else {
                    mc.setScreen(null);
                    go(34, 5);
                }
            }
            case 41 -> {
                shot(mc, galleryName);
                galleryIndex++;
                go(40, 2);
            }
            case 34 -> {
                // Transitions and filters: runs its own sub-steps, then the test goes on with the overlays.
                if (effects(mc)) {
                    go(50, 5);
                }
            }
            case 50 -> {
                overlays(mc, p);
                go(51, 20);
            }
            case 51 -> {
                ReplaySession s = ReplaySession.current();
                logOverlays(s, "overlays at cursor " + s.playback().cursor());
                shot(mc, "overlay-1");
                // Into the fade in of the fixed title: a quarter of its opacity.
                s.playback().seek(25);
                go(52, 20);
            }
            case 52 -> {
                ReplaySession s = ReplaySession.current();
                logOverlays(s, "overlays during the fade, cursor " + s.playback().cursor());
                shot(mc, "overlay-2-fade");
                s.playback().seek(40);
                EditorScreen editor = new EditorScreen(s);
                mc.setScreen(editor);
                editor.openOverlay(s.project().overlays.get(0).id);
                go(53, 10);
            }
            case 53 -> {
                shot(mc, "ui-overlays-" + com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.get().name().toLowerCase(java.util.Locale.ROOT));
                mc.setScreen(null);
                go(54, 5);
            }
            case 54 -> {
                // World override: night and thunder at the play head, then back to the recorded values.
                ReplaySession s = ReplaySession.current();
                logLevelWeather(mc, "world recorded");
                com.vyrriox.justreplay.editor.WorldSettings w = s.project().world;
                w.timeMode = com.vyrriox.justreplay.editor.WorldSettings.TIME_FIXED;
                w.time = 18000.0F;
                w.weather = com.vyrriox.justreplay.editor.WorldSettings.WEATHER_THUNDER;
                w.rain = 1.0F;
                w.thunder = 1.0F;
                s.saveProject();
                go(55, 10);
            }
            case 55 -> {
                logLevelWeather(mc, "world override (expect dayTime%24000=18000 rain=1 thunder=1)");
                shot(mc, "world-night-thunder");
                ReplaySession s = ReplaySession.current();
                s.project().world = new com.vyrriox.justreplay.editor.WorldSettings();
                s.saveProject();
                go(56, 10);
            }
            case 56 -> {
                logLevelWeather(mc, "world cleared (expect the recorded values back)");
                go(57, 5);
            }
            case 57 -> {
                // Entity edits: hide one recorded living entity, move another (the recording player counts), and check
                // render and sound gains.
                ReplaySession s = ReplaySession.current();
                // The two closest to the camera, in front of it first: the first ones of the level's list may stand
                // far away or behind the camera and never be drawn in the frames the check counts.
                java.util.List<Entity> living = new java.util.ArrayList<>();
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player || com.vyrriox.justreplay.editor.ActorManager.isActorEntity(e)
                            || !(e instanceof net.minecraft.world.entity.LivingEntity)) {
                        continue;
                    }
                    living.add(e);
                }
                Vec3 eye = mc.player.getEyePosition();
                Vec3 look = mc.player.getViewVector(1.0F);
                living.sort(java.util.Comparator.comparingDouble(e -> viewScore(eye, look, e)));
                hiddenMob = living.isEmpty() ? null : living.get(0);
                movedMob = living.size() < 2 ? null : living.get(1);
                skippedBefore = com.vyrriox.justreplay.editor.EntityEdits.skippedRenders();
                offsetBefore = com.vyrriox.justreplay.editor.EntityEdits.offsetRenders();
                if (hiddenMob != null) {
                    ReplayProject.EntityEdit hide = new ReplayProject.EntityEdit(hiddenMob.getUUID().toString(), "test hidden");
                    hide.hidden = true;
                    s.project().entityEdits.add(hide);
                }
                if (movedMob != null) {
                    ReplayProject.EntityEdit move = new ReplayProject.EntityEdit(movedMob.getUUID().toString(), "test moved");
                    move.offsets.add(new ReplayProject.ActorKeyframe(0, 0, 2, 0, 90, 0));
                    move.soundGain = 2.5F;
                    s.project().entityEdits.add(move);
                }
                log("entity edits: hidden {} moved {}", hiddenMob, movedMob);
                go(58, 20);
            }
            case 58 -> {
                ReplaySession s = ReplaySession.current();
                int skipped = com.vyrriox.justreplay.editor.EntityEdits.skippedRenders() - skippedBefore;
                int offset = com.vyrriox.justreplay.editor.EntityEdits.offsetRenders() - offsetBefore;
                log("entity edits: skipped renders {} -> {}, offset renders {} -> {}", skipped,
                        hiddenMob == null || skipped > 0 ? "OK" : "FAIL", offset, movedMob == null || offset > 0 ? "OK" : "FAIL");
                if (hiddenMob != null) {
                    float g = com.vyrriox.justreplay.editor.EntityEdits.soundGainAt(hiddenMob.getX(), hiddenMob.getY(), hiddenMob.getZ());
                    log("entity edits: sound gain at the hidden mob {} -> {}", g, g == 0.0F ? "OK" : "FAIL");
                }
                if (movedMob != null) {
                    float g = com.vyrriox.justreplay.editor.EntityEdits.soundGainAt(movedMob.getX(), movedMob.getY(), movedMob.getZ());
                    log("entity edits: sound gain at the adjusted mob {} -> {}", g, Math.abs(g - 2.5F) < 0.001F ? "OK" : "FAIL");
                }
                shot(mc, "entity-edits");
                s.project().entityEdits.clear();
                hiddenMob = null;
                movedMob = null;
                go(70, 5);
            }
            case 70 -> {
                checkVisuals(mc, ReplaySession.current());
                go(71, 20);
            }
            case 71 -> {
                ReplaySession s = ReplaySession.current();
                int skipped = com.vyrriox.justreplay.editor.EntityEdits.skippedRenders() - skippedBefore;
                log("visuals: entities of the hidden type {} skipped {} renders -> {}", s.project().hiddenEntityTypes, skipped,
                        hiddenMob == null || skipped > 0 ? "OK" : "FAIL");
                // Night vision, no fog and the outline: for the owner to look at.
                shot(mc, "visuals-night-vision-no-fog-outline");
                com.vyrriox.justreplay.editor.WorldSettings w = s.project().world;
                w.nightVision = false;
                w.fog = com.vyrriox.justreplay.editor.WorldSettings.FOG_NORMAL;
                w.particles = com.vyrriox.justreplay.editor.WorldSettings.PARTICLES_ALL;
                w.hiddenParticles.clear();
                s.project().hiddenEntityTypes.clear();
                s.project().entityEdits.clear();
                hiddenMob = null;
                movedMob = null;
                go(90, 5);
            }
            case 90 -> {
                // Markers: three made with the model, one pinned in the world by the Spot tool of the Markers tab.
                ReplaySession s = ReplaySession.current();
                s.playback().setPaused(true);
                s.setCameraPath(false);
                s.playback().seek(40);
                int duration = s.playback().duration();
                com.vyrriox.justreplay.editor.Marker moment = s.project().addMarker(com.vyrriox.justreplay.editor.Marker.instant(30, "SelfTest moment"));
                moment.note = "Wide shot of the build,\nthen cut to the player";
                com.vyrriox.justreplay.editor.Marker stretch = s.project().addMarker(com.vyrriox.justreplay.editor.Marker.range(20, 80, duration, "SelfTest stretch"));
                com.vyrriox.justreplay.editor.Marker hero = s.project().addMarker(com.vyrriox.justreplay.editor.Marker.entity(40, com.vyrriox.justreplay.editor.Marker.RECORDER, "Recording player", "SelfTest hero"));
                hero.note = "Follow this player";
                hero.color = 0x5BE49B;
                var backwards = stretch.setRange(80, 20, duration);
                var pastEnd = stretch.setRange(20, duration + 40, duration);
                boolean kept = stretch.tick == 20 && stretch.end == 80 && com.vyrriox.justreplay.editor.Marker.RANGE.equals(stretch.type);
                log("marker ranges refused: 80..20 {}, 20..{} {}, stretch kept at {}..{} -> {}", backwards, duration + 40, pastEnd,
                        stretch.tick, stretch.end, backwards == com.vyrriox.justreplay.editor.Marker.RangeCheck.EMPTY && pastEnd == com.vyrriox.justreplay.editor.Marker.RangeCheck.PAST_END && kept ? "OK" : "FAIL");
                s.saveProject();
                // The camera looks down at the stairs: the Spot tool pins the next marker where the crosshair hits.
                com.vyrriox.justreplay.replay.Playback.placeCamera(p, new Vec3(stairPos.getX() + 0.5, stairPos.getY() + 4.0, stairPos.getZ() - 3.5), 0.0F, 45.0F);
                EditorScreen editor = new EditorScreen(s);
                mc.setScreen(editor);
                editor.startMarkerToolForTest(false);
                log("marker tool started: active={} screen={}", com.vyrriox.justreplay.gui.EntityTool.isActive(),
                        mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
                next(15);
            }
            case 91 -> {
                ReplaySession s = ReplaySession.current();
                boolean confirmed = com.vyrriox.justreplay.gui.EntityTool.confirmForTest();
                com.vyrriox.justreplay.editor.Marker spot = null;
                for (com.vyrriox.justreplay.editor.Marker m : s.project().markers) {
                    if (m.isPoint()) {
                        spot = m;
                    }
                }
                Vec3 stairs = Vec3.atCenterOf(stairPos);
                boolean pinned = spot != null && Math.abs(spot.x - stairs.x) < 6 && Math.abs(spot.y - stairs.y) < 6 && Math.abs(spot.z - stairs.z) < 8
                        && spot.dimension.equals(com.vyrriox.justreplay.editor.MarkerRenderer.dimension()) && spot.tick == s.playback().cursor();
                log("marker pinned by the Spot tool: confirmed={} screen={} {} -> {}", confirmed,
                        mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(),
                        spot == null ? "none" : describeMarkers(java.util.List.of(spot)), pinned ? "OK" : "FAIL");
                if (spot != null) {
                    spot.note = "Orbit around the stairs";
                    s.saveProject();
                }
                // What the editor saved is what a new reading of project.json gives back.
                java.util.List<String> saved = describeMarkers(s.project().markers);
                java.util.List<String> read = describeMarkers(ReplayStorage.readProject(s.dir()).markers);
                long made = s.project().markers.stream().filter(m -> m.name.startsWith("SelfTest") || m.isPoint()).count();
                log("markers read back from project.json: {} -> {}", read, read.equals(saved) && made == 4 ? "OK" : "FAIL");
                if (mc.screen instanceof EditorScreen e && spot != null) {
                    e.openMarker(spot.id);
                }
                next(10);
            }
            case 92 -> {
                shot(mc, "ui-markers-selected");
                mc.setScreen(null);
                // The world shot frames the spot and the recording player, so both markers show.
                ReplaySession s = ReplaySession.current();
                Entity recorder = s.findRecorder(mc);
                com.vyrriox.justreplay.editor.Marker spot = null;
                for (com.vyrriox.justreplay.editor.Marker m : s.project().markers) {
                    if (m.isPoint()) {
                        spot = m;
                    }
                }
                if (recorder != null && spot != null) {
                    Vec3 at = new Vec3(spot.x, spot.y, spot.z);
                    Vec3 mid = recorder.position().add(at).scale(0.5);
                    double span = recorder.position().distanceTo(at);
                    com.vyrriox.justreplay.replay.Playback.placeCamera(p, mid.add(0, 2.0 + span * 0.35, -(5.0 + span * 0.9)), 0.0F, 22.0F);
                }
                markerCounters = com.vyrriox.justreplay.editor.MarkerRenderer.counters();
                next(20);
            }
            case 93 -> {
                ReplaySession s = ReplaySession.current();
                long[] now = com.vyrriox.justreplay.editor.MarkerRenderer.counters();
                log("markers drawn in the world: {} of {} level renders -> {}", now[1] - markerCounters[1], now[0] - markerCounters[0],
                        now[1] > markerCounters[1] ? "OK" : "FAIL");
                com.vyrriox.justreplay.editor.Marker hero = null;
                for (com.vyrriox.justreplay.editor.Marker m : s.project().markers) {
                    if (m.isEntity()) {
                        hero = m;
                    }
                }
                Entity followed = hero == null ? null : com.vyrriox.justreplay.editor.MarkerRenderer.entity(s, hero);
                Entity recorder = s.findRecorder(mc);
                log("entity marker on the recording player finds it: {} -> {}", followed == null ? "nothing" : followed.position(),
                        followed != null && followed == recorder ? "OK" : "FAIL");
                shot(mc, "markers-world");
                go(95, 5);
            }
            case 95 -> {
                // MCP server: switched on (on a free port) while the replay and its markers are open, then asked over HTTP.
                mcpSaved = true;
                mcpWasEnabled = com.vyrriox.justreplay.config.JRConfig.MCP.enabled.get();
                mcpOldPort = com.vyrriox.justreplay.config.JRConfig.MCP.port.get();
                mcpOldToken = com.vyrriox.justreplay.config.JRConfig.MCP.token.get();
                mcpPort = freeLocalPort();
                com.vyrriox.justreplay.config.JRConfig.MCP.port.set(mcpPort);
                com.vyrriox.justreplay.config.JRConfig.MCP.enabled.set(true);
                log("mcp server switched on in the settings, port {}", mcpPort);
                next(5);
            }
            case 96 -> {
                com.vyrriox.justreplay.mcp.McpHost host = com.vyrriox.justreplay.mcp.McpClient.host();
                String token = com.vyrriox.justreplay.config.JRConfig.MCP.token.get();
                boolean listening = host.isListening() && host.port() == mcpPort && localPortOpen(mcpPort);
                log("mcp server listening on 127.0.0.1:{} with a generated token of {} characters -> {}", host.port(), token.length(),
                        listening && com.vyrriox.justreplay.mcp.McpToken.usable(token) ? "OK" : "FAIL");
                ReplaySession s = ReplaySession.current();
                com.vyrriox.justreplay.editor.Marker moment = null;
                for (com.vyrriox.justreplay.editor.Marker m : s.project().markers) {
                    if (m.name.equals("SelfTest moment")) {
                        moment = m;
                    }
                }
                if (!listening || moment == null) {
                    log("mcp checks skipped: listening={} marker found={} -> FAIL", listening, moment != null);
                    go(13, 5);
                    return;
                }
                mcpBadgeBefore = com.vyrriox.justreplay.mcp.McpClient.badgeFrames();
                mcpChecks = com.vyrriox.justreplay.mcp.McpProbe.start(mcpPort, token, new com.vyrriox.justreplay.mcp.McpProbe.Expect(
                        s.dir().getFileName().toString(), s.project().markers.size(), moment.id, moment.name, moment.note));
                next(1);
            }
            case 97 -> {
                // The checks need this thread to keep ticking: every call reaching the game is answered at a client tick.
                if (!mcpChecks.isDone()) {
                    return;
                }
                for (String line : mcpChecks.join()) {
                    log(line);
                }
                long drawn = com.vyrriox.justreplay.mcp.McpClient.badgeFrames() - mcpBadgeBefore;
                log("mcp badge drawn in the HUD while listening: {} frames -> {}", drawn, drawn > 0 ? "OK" : "FAIL");
                shot(mc, "mcp-badge");
                go(101, 5);
            }
            case 101 -> {
                // MCP observation: the entities of another tick, frames drawn by the export path and by the window, read
                // back by the game's own decoder; the play head and the screen must be as they were afterwards.
                ReplaySession s = ReplaySession.current();
                mcpObserveCursor = s.playback().cursor();
                mcpObserveScreen = mc.screen;
                mcpGrabsBefore = com.vyrriox.justreplay.export.FrameGrab.grabbed();
                log("mcp observation checks start at play head {} on screen {}", mcpObserveCursor, mc.screen == null ? "none"
                        : mc.screen.getClass().getSimpleName());
                mcpObserve = com.vyrriox.justreplay.mcp.McpProbe.observe(mcpPort, com.vyrriox.justreplay.config.JRConfig.MCP.token.get(),
                        new com.vyrriox.justreplay.mcp.McpProbe.Look(MCP_OBSERVE_TICK, java.util.List.of("minecraft:villager", "minecraft:player"),
                                true, 320, 180, Mods.mcpWorn()));
                next(1);
            }
            case 102 -> {
                // The frames need this thread to keep ticking and drawing.
                if (!mcpObserve.isDone()) {
                    return;
                }
                com.vyrriox.justreplay.mcp.McpProbe.Observation o = mcpObserve.join();
                for (String line : o.lines()) {
                    log(line);
                }
                for (com.vyrriox.justreplay.mcp.McpProbe.Picture picture : o.pictures()) {
                    checkMcpPicture(mc, picture);
                }
                long grabs = com.vyrriox.justreplay.export.FrameGrab.grabbed() - mcpGrabsBefore;
                log("mcp frames handed over: {} (expected {}) -> {}", grabs, o.pictures().size(), grabs == 3 ? "OK" : "FAIL");
                ReplaySession s = ReplaySession.current();
                boolean sameScreen = mc.screen == mcpObserveScreen;
                log("mcp observation left the replay as it was: play head {} (was {}), same screen={}, export running={} -> {}",
                        s.playback().cursor(), mcpObserveCursor, sameScreen, VideoExporter.isActive(),
                        s.playback().cursor() == mcpObserveCursor && sameScreen && !VideoExporter.isActive() ? "OK" : "FAIL");
                checkMcpCameraMath(mc);
                shot(mc, "mcp-observe");
                go(13, 5);
            }
            case 13 -> {
                mc.setScreen(null);
                ReplaySession s = ReplaySession.current();
                s.setCameraPath(false);
                // Ticks 20 to 80 are 0:01 to 0:04. A file left by an earlier run would push this export to _2.
                exportFile = ReplayStorage.exportRoot().resolve(com.vyrriox.justreplay.config.JRConfig.EXPORT_RANGE_IN_NAME.get()
                        ? "selftest_00m01s-00m04s.mp4" : "selftest.mp4");
                Files.deleteIfExists(exportFile);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.set(18);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.set(60);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.set(com.vyrriox.justreplay.export.ExportResolution.FULL_HD_1080);
                com.vyrriox.justreplay.editor.overlay.OverlayRenderer.resetExportFrames();
                var error = VideoExporter.start(s, 20, 80, "selftest");
                markerExportStart = com.vyrriox.justreplay.editor.MarkerRenderer.counters();
                markerExportLast = markerExportStart;
                mcpListeningAtExport = com.vyrriox.justreplay.mcp.McpClient.isListening();
                mcpBadgeExportStart = com.vyrriox.justreplay.mcp.McpClient.badgeFrames();
                mcpBadgeExportLast = mcpBadgeExportStart;
                log("export start error={}", error == null ? "none" : error.getString());
                if (error == null) {
                    Path output = VideoExporter.current().output();
                    log("export file name {} (expected {}): {}", output.getFileName(), exportFile.getFileName(),
                            output.equals(exportFile) ? "OK" : "FAIL");
                }
                next(1);
            }
            case 14 -> {
                sampleExportClocks();
                if (VideoExporter.isActive()) {
                    markerExportLast = com.vyrriox.justreplay.editor.MarkerRenderer.counters();
                    mcpBadgeExportLast = com.vyrriox.justreplay.mcp.McpClient.badgeFrames();
                }
                shotVeil(mc);
                if (!VideoExporter.isActive()) {
                    next(200);
                }
            }
            case 15 -> {
                Path mp4 = exportFile;
                log("export file exists={} size={}", Files.exists(mp4), Files.exists(mp4) ? Files.size(mp4) : 0);
                log("export audio clips mixed={} (expected 2), audio mux log={}", com.vyrriox.justreplay.export.ExportAudio.lastMixedClips(),
                        Files.exists(mp4.resolveSibling(mp4.getFileName() + ".audio.log")));
                log("mods after export: {}", Mods.state(mc, ReplaySession.current()));
                checkVeil();
                if (exportClockFirst < 0) {
                    log("clock export: no sample while the export ran FAIL");
                } else {
                    checkClockRise("export", exportClockLast - exportClockFirst, 1, 1, exportClockGecko[0], exportClockGecko[1], exportClockIris[0], exportClockIris[1]);
                }
                log("local effects heard in the replay: bell={} anvil={} footsteps={} of which the recording player's own={} -> {}", localBell,
                        localAnvil, replaySteps, recorderSteps, localBell > 0 && localAnvil > 0 && recorderSteps > 0 ? "OK" : "FAIL");
                log("export frames showing overlays: {} ({} overlays in the project)",
                        com.vyrriox.justreplay.editor.overlay.OverlayRenderer.exportFrames(), ReplaySession.current().project().overlays.size());
                log("markers kept out of the export: {} frames drew markers while exporting, {} frames left them out -> {}",
                        markerExportLast[1] - markerExportStart[1], markerExportLast[2] - markerExportStart[2],
                        markerExportLast[1] == markerExportStart[1] && markerExportLast[2] > markerExportStart[2] ? "OK" : "FAIL");
                go(98, 5);
            }
            case 98 -> {
                long drawn = mcpBadgeExportLast - mcpBadgeExportStart;
                log("mcp badge kept out of the export: server listening={}, {} frames drew it while exporting -> {}", mcpListeningAtExport,
                        drawn, mcpListeningAtExport && drawn == 0 ? "OK" : "FAIL");
                com.vyrriox.justreplay.config.JRConfig.MCP.enabled.set(false);
                next(5);
            }
            case 99 -> {
                com.vyrriox.justreplay.mcp.McpHost host = com.vyrriox.justreplay.mcp.McpClient.host();
                boolean stopped = !host.isListening() && mcpPort > 0 && !localPortOpen(mcpPort);
                log("mcp server stopped when switched off, port {} closed -> {}", mcpPort, stopped ? "OK" : "FAIL");
                restoreMcpConfig();
                go(94, 5);
            }
            case 94 -> {
                // Deleting: the spot through the Delete key of the Markers tab, the others by id.
                ReplaySession s = ReplaySession.current();
                com.vyrriox.justreplay.editor.Marker spot = null;
                for (com.vyrriox.justreplay.editor.Marker m : s.project().markers) {
                    if (m.isPoint()) {
                        spot = m;
                    }
                }
                EditorScreen editor = new EditorScreen(s);
                mc.setScreen(editor);
                if (spot != null) {
                    editor.deleteMarkerForTest(spot.id);
                }
                boolean spotGone = spot != null && s.project().marker(spot.id) == null;
                int removed = 0;
                for (com.vyrriox.justreplay.editor.Marker m : new java.util.ArrayList<>(s.project().markers)) {
                    if (m.name.startsWith("SelfTest") && s.project().removeMarker(m.id)) {
                        removed++;
                    }
                }
                s.saveProject();
                java.util.List<com.vyrriox.justreplay.editor.Marker> left = ReplayStorage.readProject(s.dir()).markers;
                boolean gone = left.stream().noneMatch(m -> m.isPoint() || m.name.startsWith("SelfTest"));
                log("markers deleted (spot by the Delete key: {}, {} more by id), left in project.json: {} -> {}", spotGone, removed,
                        left.stream().map(m -> m.name + "@" + m.tick).toList(), spotGone && removed == 3 && gone ? "OK" : "FAIL");
                mc.setScreen(null);
                go(72, 5);
            }
            case 72 -> {
                // Speed track: two replay seconds at x0.5 make four seconds of video, 241 frames at 60 fps.
                ReplaySession s = ReplaySession.current();
                s.project().speed = new com.vyrriox.justreplay.editor.SpeedTrack();
                s.project().speed.setKey(0, 0.5F);
                Files.deleteIfExists(ReplayStorage.exportRoot().resolve("selftest_slow.mp4"));
                // The exact name whatever rangeInFileName says, as SceneJob does.
                slowWait = 0;
                var error = VideoExporter.start(s, 20, 60, "selftest_slow", VideoExporter.Settings.fromConfig(), false);
                slowExport = VideoExporter.current();
                log("slow motion export start error={}", error == null ? "none" : error.getString());
                next(1);
            }
            case 73 -> {
                // The file is written once the finisher thread has let FFmpeg end and mixed the sound: a minute at most.
                if (!VideoExporter.isActive() && (slowExport == null || slowExport.completion().isDone() || ++slowWait > 1200)) {
                    next(1);
                }
            }
            case 74 -> {
                int frames = slowExport == null ? -1 : slowExport.frames();
                double seconds = slowExport == null ? -1 : slowExport.videoSeconds();
                Path slow = slowExport == null ? null : slowExport.output();
                boolean written = slow != null && Files.exists(slow);
                log("slow motion export: {} frames for 40 ticks at x0.5 (expected 241), {} s of video, file {} written {} -> {}", frames,
                        seconds, slow == null ? "none" : slow.getFileName(), written,
                        frames == 241 && Math.abs(seconds - 4.0) < 1.0E-6 && written ? "OK" : "FAIL");
                ReplaySession.current().project().speed = new com.vyrriox.justreplay.editor.SpeedTrack();
                slowExport = null;
                checkSkippedLine(ReplaySession.current());
                mc.level.disconnect();
                // The host stops by itself as soon as its player leaves: leave once it has (10 s at most), which once
                // froze the game on Fabric when a resource reload came in between. The client must not wait for it.
                net.minecraft.client.server.IntegratedServer host = mc.getSingleplayerServer();
                long until = System.currentTimeMillis() + 10_000L;
                while (host != null && host.getRunningThread().isAlive() && System.currentTimeMillis() < until) {
                    java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
                }
                boolean hostStopped = host == null || !host.getRunningThread().isAlive();
                mc.clearLevel(new TitleScreen());
                log("left the replay once its host had stopped: {} -> {}", hostStopped, hostStopped ? "OK" : "FAIL");
                go(16, 60);
            }
            case 16 -> {
                if (atMenu(mc)) {
                    Mods.checkCulling("after leaving the replay", true);
                    if (clip == null) {
                        step = 19;
                        return;
                    }
                    // The clip is named through the window a player gets, then opened under its new name.
                    offerRename();
                    go(80, 10);
                }
            }
            case 80 -> {
                renameWait = 0;
                go(typeRename(mc) ? 81 : 82, 2);
            }
            case 81 -> {
                if (ReplayNaming.renaming() && ++renameWait < 200) {
                    return;
                }
                checkRename();
                go(82, 5);
            }
            case 82 -> {
                if (atMenu(mc)) {
                    ReplaySession.open(clip, mc.screen);
                    log("buffer clip opening");
                    go(17, 40);
                }
            }
            case 17 -> {
                if (ReplaySession.isRunning() && p != null) {
                    next(60);
                } else {
                    failIfReplayDied(mc);
                }
            }
            case 18 -> {
                if (mc.getOverlay() != null) {
                    // The clip's resource packs are loading: leaving now would leave the loading screen up for good.
                    return;
                }
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "buffer clip playing");
                shot(mc, "6-buffer-clip");
                s.playback().seek(Math.max(0, s.playback().duration() - 10));
                logWorld(mc, s, "buffer clip after forward seek");
                s.playback().seek(5);
                logWorld(mc, s, "buffer clip after backward seek");
                mc.level.disconnect();
                mc.clearLevel(new TitleScreen());
                go(103, 60);
            }
            case 103 -> {
                // MCP control, driven through MCP only: the probe opens the buffer clip from the menu, goes to a tick,
                // places the view, adds a camera keyframe, an actor and a marker, saves and reads them back, undoes and
                // redoes, starts an export and cancels it, then closes the replay. The game switches the server on and
                // checks project.json afterwards.
                if (!atMenu(mc)) {
                    if (++mcpSteerWait > 2400) {
                        log("mcp control checks: the menu never came back (screen {}, loading overlay {}) -> FAIL",
                                mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(), mc.getOverlay() != null);
                        go(19, 5);
                    }
                    return;
                }
                if (clip == null) {
                    log("mcp control checks skipped: no test replay (the buffer clip was not saved) -> FAIL");
                    go(19, 5);
                    return;
                }
                ReplayProject before = ReplayStorage.readProject(clip);
                com.vyrriox.justreplay.format.ReplayMeta clipMeta = ReplayStorage.readMeta(clip);
                // Markers placed while recording join the project the first time the replay opens.
                mcpSteerMarkers = before.markers.size() + (before.recordingMarkersImported || clipMeta == null || clipMeta.markers == null
                        ? 0 : clipMeta.markers.size());
                Files.deleteIfExists(ReplayStorage.exportRoot().resolve("selftest_mcp.mp4"));
                mcpSaved = true;
                mcpWasEnabled = com.vyrriox.justreplay.config.JRConfig.MCP.enabled.get();
                mcpOldPort = com.vyrriox.justreplay.config.JRConfig.MCP.port.get();
                mcpOldToken = com.vyrriox.justreplay.config.JRConfig.MCP.token.get();
                mcpPort = freeLocalPort();
                com.vyrriox.justreplay.config.JRConfig.MCP.port.set(mcpPort);
                com.vyrriox.justreplay.config.JRConfig.MCP.enabled.set(true);
                log("mcp server switched on again for the control checks, port {}", mcpPort);
                next(5);
            }
            case 104 -> {
                com.vyrriox.justreplay.mcp.McpHost host = com.vyrriox.justreplay.mcp.McpClient.host();
                if (!host.isListening() || host.port() != mcpPort) {
                    log("mcp control checks skipped: the server does not listen on port {} -> FAIL", mcpPort);
                    restoreMcpConfig();
                    go(19, 5);
                    return;
                }
                log("mcp control checks start on '{}' ({} markers), from the menu", clip.getFileName(), mcpSteerMarkers);
                mcpSteer = com.vyrriox.justreplay.mcp.McpProbe.steer(mcpPort, com.vyrriox.justreplay.config.JRConfig.MCP.token.get(),
                        new com.vyrriox.justreplay.mcp.McpProbe.Steer(clip.getFileName().toString(), mcpSteerMarkers, steerTick(clip),
                                "selftest_mcp"));
                next(1);
            }
            case 105 -> {
                // The checks need this thread to keep ticking: the replay opens, plays, exports and closes meanwhile.
                if (!mcpSteer.isDone()) {
                    return;
                }
                com.vyrriox.justreplay.mcp.McpProbe.Steering steering = mcpSteer.join();
                for (String line : steering.lines()) {
                    log(line);
                }
                ReplayProject saved = ReplayStorage.readProject(clip);
                ReplayProject.Camera camera = saved.camera(steering.cameraId());
                boolean keyframe = camera != null && camera.keyframeAt(steering.keyframeTick()) != null;
                boolean actor = saved.actors.stream().anyMatch(a -> a.id.equals(steering.actorId()));
                boolean marker = saved.marker(steering.markerId()) != null;
                log("mcp edits found in project.json: keyframe at tick {}={} actor={} marker={} -> {}", steering.keyframeTick(), keyframe,
                        actor, marker, keyframe && actor && marker ? "OK" : "FAIL");
                Path cancelled = steering.exportFile().isEmpty() ? null : ReplayStorage.exportRoot().resolve(steering.exportFile());
                log("mcp cancelled export left no file: {} -> {}", cancelled == null ? "no export" : cancelled.getFileName(),
                        cancelled != null && !Files.exists(cancelled) ? "OK" : "FAIL");
                log("mcp control left the game on the menu: replay open={} -> {}", ReplaySession.current() != null,
                        ReplaySession.current() == null && atMenu(mc) ? "OK" : "FAIL");
                com.vyrriox.justreplay.config.JRConfig.MCP.enabled.set(false);
                restoreMcpConfig();
                go(19, 5);
            }
            case 19 -> {
                com.vyrriox.justreplay.config.JRConfig.REPLAY_BUFFER.set(false);
                restoreUserConfig();
                log("DONE");
                Files.deleteIfExists(FLAG);
                step = 100;
                mc.stop();
            }
            default -> {
            }
        }
    }

    /**
     * Video effects: a cross dissolve on the cut to the follow camera, the Cinematic look with a keyframed vignette
     * and start and end fades. Screenshots of the same frame with the effects off and on, during the dissolve while
     * playing, and after seeking into the dissolve (the outgoing frame is then captured on the fly). Returns true
     * when done; the export that follows bakes the same effects.
     */
    private static boolean effects(Minecraft mc) {
        ReplaySession s = ReplaySession.current();
        ReplayProject project = s.project();
        switch (fxStep) {
            case 0 -> {
                mc.setScreen(null);
                s.setCameraPath(true);
                s.playback().setPaused(true);
                s.playback().seek(55);
                project.effectsEnabled = false;
                ReplayProject.Cut cut = project.cuts.get(project.cutIndexAt(65));
                cut.transition = com.vyrriox.justreplay.editor.fx.TransitionType.DISSOLVE.name();
                cut.transitionTicks = 20;
                project.effects.removeIf(e -> true);
                project.effects.addAll(com.vyrriox.justreplay.editor.fx.FxPresets.CINEMATIC.build(""));
                for (com.vyrriox.justreplay.editor.fx.FxEffect e : project.effects) {
                    if (e.fxType() == com.vyrriox.justreplay.editor.fx.FxType.VIGNETTE) {
                        com.vyrriox.justreplay.editor.fx.FxParam amount = e.fxType().param("amount");
                        e.setKey(amount, 20, 0.1F);
                        e.setKey(amount, 80, 0.85F);
                        log("fx vignette keyed: t=20 {} t=50 {} t=80 {}", e.value(amount, 20), e.value(amount, 50), e.value(amount, 80));
                    }
                }
                project.fadeIn = 10;
                project.fadeOut = 10;
                s.saveProject();
                log("fx shaders ready={} effects={} transition on cut {} = {} {}t", com.vyrriox.justreplay.editor.fx.FxEngine.ready(),
                        project.effects.size(), cut.tick, cut.transition, cut.transitionTicks);
                log("audio crossfade power at mid overlap: {}", Math.pow(com.vyrriox.justreplay.editor.audio.AudioMixer.crossfade(5, 100, 10, 0), 2)
                        + Math.pow(com.vyrriox.justreplay.editor.audio.AudioMixer.crossfade(95, 100, 0, 10), 2));
                fxStep++;
                wait = 30;
            }
            case 1 -> {
                shot(mc, "fx-1-off");
                project.effectsEnabled = true;
                fxStep++;
                wait = 20;
            }
            case 2 -> {
                shot(mc, "fx-2-cinematic");
                s.playback().seek(58);
                s.playback().setPaused(false);
                fxStep++;
                wait = 12;
            }
            case 3 -> {
                shot(mc, "fx-3-dissolve-playing");
                log("fx dissolve screenshot at cursor {}", s.playback().cursor());
                s.playback().setPaused(true);
                s.playback().seek(70);
                fxStep++;
                wait = 30;
            }
            case 4 -> {
                shot(mc, "fx-4-dissolve-after-seek");
                log("fx dissolve after seek at cursor {}", s.playback().cursor());
                s.setCameraPath(false);
                fxStep++;
                return true;
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    /**
     * Texts and images in the world: a label following the recording player (its scale keyframed), a generated
     * PNG sticker lying on the gold block, a fixed title at a position with fades shown through walls, and one
     * text anchored by a ray like the placement tool does. The camera then looks at them from the north.
     */
    private static void overlays(Minecraft mc, LocalPlayer p) throws java.io.IOException {
        ReplaySession s = ReplaySession.current();
        mc.setScreen(null);
        s.setCameraPath(false);
        s.playback().setPaused(true);
        s.playback().seek(40);
        Path png = com.vyrriox.justreplay.editor.overlay.ImageLibrary.folder().resolve("selftest-sticker.png");
        try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(64, 64, true)) {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    double d = Math.hypot(x - 31.5, y - 31.5);
                    // A gradient disc with a white ring on a transparent square (ABGR).
                    int abgr = d > 30 ? 0 : d > 26 ? 0xFFFFFFFF : 0xFF000000 | 200 << 16 | (y * 4) << 8 | (x * 4);
                    image.setPixelRGBA(x, y, abgr);
                }
            }
            image.writeToFile(png);
        }
        com.vyrriox.justreplay.editor.overlay.ImageLibrary.reload();
        net.minecraft.core.BlockPos base = modsBase != null ? modsBase : p.blockPosition();
        net.minecraft.core.BlockPos gold = base.offset(2, 0, 0);
        int duration = s.playback().duration();
        Overlay label = new Overlay(Overlay.TEXT);
        label.name = "Selftest label";
        label.text = "&6Just Replay\n&fself test";
        label.anchor = Overlay.ENTITY;
        label.target = Overlay.RECORDER;
        label.targetName = "Recorder";
        label.setBase(Overlay.OFFSET_Y, 0.4F);
        label.start = 20;
        label.end = 80;
        label.fadeIn = 0;
        label.fadeOut = 0;
        label.setKey(Overlay.SCALE, 20, 0.8F);
        label.setKey(Overlay.SCALE, 80, 1.4F);
        Overlay sticker = new Overlay(Overlay.IMAGE);
        sticker.name = "Selftest sticker";
        sticker.image = png.getFileName().toString();
        sticker.anchor = Overlay.BLOCK;
        sticker.blockX = gold.getX();
        sticker.blockY = gold.getY();
        sticker.blockZ = gold.getZ();
        sticker.face = "UP";
        sticker.facing = Overlay.FLAT;
        sticker.width = 1.0F;
        sticker.start = 0;
        sticker.end = duration;
        sticker.fadeIn = 0;
        sticker.fadeOut = 0;
        Overlay title = new Overlay(Overlay.TEXT);
        title.name = "Selftest title";
        title.text = "&lFixed title\nfades in and out";
        title.anchor = Overlay.POSITION;
        title.x = base.getX() + 0.5;
        title.y = base.getY() + 3.5;
        title.z = base.getZ() + 4.5;
        title.facing = Overlay.FIXED;
        title.setBase(Overlay.YAW, 0.0F);
        title.throughWalls = true;
        title.fontSize = 0.4F;
        title.color = 0x5AB4FF;
        title.start = 20;
        title.end = 80;
        title.fadeIn = 20;
        title.fadeOut = 20;
        s.project().overlays.removeIf(o -> o.name.startsWith("Selftest"));
        s.project().overlays.add(label);
        s.project().overlays.add(sticker);
        s.project().overlays.add(title);
        // Anchored by a ray from the north towards the gold block, as the placement tool does on a click.
        Vec3 eye = new Vec3(base.getX() + 0.5, base.getY() + 4.6, base.getZ() - 4.0);
        Overlay aimed = new Overlay(Overlay.TEXT);
        aimed.name = "Selftest aimed";
        aimed.text = "aimed";
        aimed.start = 20;
        aimed.end = 80;
        com.vyrriox.justreplay.gui.OverlayPlacementTool.anchorAt(s, aimed, eye, Vec3.atCenterOf(gold).subtract(eye), 4.0, true);
        s.project().overlays.add(aimed);
        s.saveProject();
        var loaded = com.vyrriox.justreplay.editor.overlay.ImageLibrary.get(sticker.image);
        log("overlays: {} in project, sticker image {}, aimed overlay anchored {} {}", s.project().overlays.size(),
                loaded == null ? "not loaded" : loaded.width() + "x" + loaded.height(), aimed.anchor,
                Overlay.BLOCK.equals(aimed.anchor) ? aimed.blockX + " " + aimed.blockY + " " + aimed.blockZ + " " + aimed.face : aimed.target);
        com.vyrriox.justreplay.replay.Playback.placeCamera(p, new Vec3(base.getX() + 0.5, base.getY() + 3.0, base.getZ() - 4.0), 0.0F, 25.0F);
    }

    private static void logOverlays(ReplaySession s, String label) {
        double time = s.playback().cursor();
        StringBuilder sb = new StringBuilder();
        for (Overlay o : s.project().overlays) {
            Vec3 at = com.vyrriox.justreplay.editor.overlay.OverlayRenderer.position(s, o, time, 0.0F);
            sb.append(String.format(java.util.Locale.ROOT, "[%s %s %s at %s fade %.2f] ", o.displayName(), o.type, o.anchor,
                    at == null ? "unresolved" : String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", at.x, at.y, at.z), o.fade(time)));
        }
        log("{}: drawn last frame={} {}", label, com.vyrriox.justreplay.editor.overlay.OverlayRenderer.lastDrawn(), sb);
    }

    /**
     * Writes a one second 440 Hz beep (44.1 kHz mono 16-bit WAV, so decoding resamples it) into the audio folder,
     * then places it on a new audio track inside the export range, with fades and ducking.
     */
    private static void addBeepClip(ReplaySession s) throws java.io.IOException {
        Path wav = com.vyrriox.justreplay.editor.audio.AudioLibrary.folder().resolve("selftest-beep.wav");
        int rate = 44_100;
        int frames = rate;
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(44 + frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + frames * 2)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(frames * 2);
        for (int i = 0; i < frames; i++) {
            data.putShort((short) Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 0.5 * 32767));
        }
        Files.write(wav, data.array());
        com.vyrriox.justreplay.editor.audio.AudioPcm pcm = com.vyrriox.justreplay.editor.audio.AudioLibrary.getBlocking(wav.getFileName().toString());
        log("beep decoded: {} frames at 48 kHz ({} ticks), peak {}", pcm == null ? -1 : pcm.frames, pcm == null ? 0 : pcm.lengthTicks(),
                pcm == null ? 0 : pcm.peak(0, pcm.frames));
        com.vyrriox.justreplay.editor.audio.AudioTrack track = new com.vyrriox.justreplay.editor.audio.AudioTrack("Selftest");
        com.vyrriox.justreplay.editor.audio.AudioClip clip = new com.vyrriox.justreplay.editor.audio.AudioClip(wav.getFileName().toString(), 30,
                pcm == null ? 20 : pcm.lengthTicks());
        clip.fadeIn = 4;
        clip.fadeOut = 6;
        track.clips.add(clip);
        s.project().audioTracks.removeIf(t -> "Selftest".equals(t.name));
        s.project().audioTracks.add(track);
        s.project().ducking = true;
        s.project().gameVolume = 0.8F;
        // Split and trim round trip: the two halves cover the same ticks as the clip.
        com.vyrriox.justreplay.editor.audio.AudioClip right = clip.splitAt(40);
        log("split at 40: left {}..{} right {}..{} (source {}..{})", clip.start, clip.end(), right == null ? -1 : right.start,
                right == null ? -1 : right.end(), right == null ? -1 : right.in, right == null ? -1 : right.out);
        if (right != null) {
            track.clips.add(right);
        }
        int clips = 0;
        for (com.vyrriox.justreplay.editor.audio.AudioTrack t : s.project().audioTracks) {
            clips += t.clips.size();
        }
        s.saveProject();
        log("audio clips in project: {} on {} tracks", clips, s.project().audioTracks.size());
    }

    /**
     * Rename check, first part: a replay already holds the folder the new name gives, then the saved clip waits for
     * the naming window as a recording the player stops does.
     */
    private static void offerRename() throws java.io.IOException {
        renameDefault = ReplayStorage.readMeta(clip).name;
        renameDecoy = clip.resolveSibling(RENAME_FOLDER);
        // Left by an earlier run of the test.
        ReplayStorage.deleteRecursively(renameDecoy);
        ReplayStorage.deleteRecursively(clip.resolveSibling(RENAME_FOLDER + " (2)"));
        Files.createDirectories(renameDecoy);
        Files.writeString(renameDecoy.resolve("decoy.txt"), "decoy");
        ReplayNaming.queue(clip);
        log("rename: clip {} waits for its name, saved as '{}'", clip.getFileName(), renameDefault);
    }

    /**
     * Rename check, second part: the window opened by itself on the menu with the saved name selected; a name is
     * typed over it and Enter closes it. False when the window never opened.
     */
    private static boolean typeRename(Minecraft mc) throws java.io.IOException {
        com.vyrriox.justreplay.gui.TextInputScreen window = ReplayNaming.window();
        boolean open = window != null && mc.screen == window;
        log("rename: window opened with the saved name '{}' -> {}", open ? window.value() : String.valueOf(mc.screen),
                open && renameDefault.equals(window.value()) ? "OK" : "FAIL");
        if (!open) {
            ReplayStorage.deleteRecursively(renameDecoy);
            return false;
        }
        // The saved name is selected: the first letter typed replaces it, as for a player.
        for (char c : RENAME_TYPED.toCharArray()) {
            window.charTyped(c, 0);
        }
        log("rename: typed over the saved name '{}' -> {}", window.value(), RENAME_TYPED.equals(window.value()) ? "OK" : "FAIL");
        window.keyPressed(257, 0, 0);
        log("rename: Enter closes the window -> {}", mc.screen != window ? "OK" : "FAIL");
        return true;
    }

    /**
     * Rename check, last part: the clip moved to the folder after the decoy's, the list shows the typed name with
     * the same number, and the replay that had the folder first is untouched. The clip is then opened from there.
     */
    private static void checkRename() throws java.io.IOException {
        log("rename: done in the background -> {}", ReplayNaming.renaming() ? "FAIL" : "OK");
        Path renamed = clip.resolveSibling(RENAME_FOLDER + " (2)");
        boolean moved = Files.isRegularFile(renamed.resolve(ReplayStorage.STREAM)) && !Files.exists(clip);
        log("rename: folder {} -> {} -> {}", clip.getFileName(), renamed.getFileName(), moved ? "OK" : "FAIL");
        String shown = ReplayStorage.readMeta(moved ? renamed : clip).name;
        log("rename: shown name '{}' -> {}", shown, (RENAME_TYPED + " (2)").equals(shown) ? "OK" : "FAIL");
        Path decoyFile = renameDecoy.resolve("decoy.txt");
        boolean kept = Files.isRegularFile(decoyFile) && "decoy".equals(Files.readString(decoyFile));
        log("rename: the replay already in {} is untouched -> {}", renameDecoy.getFileName(), kept ? "OK" : "FAIL");
        boolean listed = ReplayStorage.list().stream().anyMatch(e -> e.dir().equals(renamed) && shown.equals(e.meta().name));
        log("rename: the replay list shows it -> {}", listed ? "OK" : "FAIL");
        ReplayStorage.deleteRecursively(renameDecoy);
        if (moved) {
            clip = renamed;
        }
    }

    /**
     * Whether the slow checkpoint asked for at recording tick 10 was handed out and the recording's folder holds a
     * checkpoint (files are written on an IO thread, right after the hand out). True when no recording runs.
     */
    private static boolean checkpointWritten() {
        Recorder capture = Recorder.capturing();
        Recorder.Recording recording = capture == null ? null : capture.recording();
        return recording == null || capture.slowCheckpointDone()
                && com.vyrriox.justreplay.format.Checkpoints.list(recording.dir()).length > 0;
    }

    /** Checks the saved instant replay opens like any replay: stream, first packet, checkpoints, duration. */
    private static void verifyClip(Minecraft mc) {
        try {
            com.vyrriox.justreplay.format.ReplayBuffer.Saved saved = clipSave.join();
            clip = saved.dir();
            com.vyrriox.justreplay.format.PacketStream stream = com.vyrriox.justreplay.format.PacketStream.open(clip);
            com.vyrriox.justreplay.format.RecordedPacket first = stream.peek();
            String firstType = first == null ? "none" : com.vyrriox.justreplay.record.PacketCodec.of(mc.getConnection())
                    .decode(first.data()).getClass().getSimpleName();
            int packets = 0;
            int lastTick = 0;
            for (com.vyrriox.justreplay.format.RecordedPacket packet; (packet = stream.peek()) != null; stream.advance()) {
                packets++;
                lastTick = packet.tick();
            }
            log("buffer clip {}: packets={} (saved {}) lastTick={} duration={} checkpoints={} first={} voice={}", clip.getFileName(),
                    packets, saved.packets(), lastTick, ReplayStorage.readMeta(clip).durationTicks,
                    java.util.Arrays.toString(com.vyrriox.justreplay.format.Checkpoints.list(clip)), firstType, saved.voiceFrames());
            // Markers placed at recording ticks 60 and 150, saved at 200: the last one sits 50 ticks before the end.
            com.vyrriox.justreplay.format.ReplayMeta clipMeta = ReplayStorage.readMeta(clip);
            java.util.List<com.vyrriox.justreplay.format.ReplayMeta.Marker> marks = clipMeta.markers == null ? java.util.List.of() : clipMeta.markers;
            com.vyrriox.justreplay.format.ReplayMeta.Marker last = marks.isEmpty() ? null : marks.get(marks.size() - 1);
            boolean lastOk = last != null && "Marker 2".equals(last.name) && Math.abs(clipMeta.durationTicks - last.tick - 50) <= 3;
            boolean spacingOk = marks.size() < 2 || Math.abs(marks.get(1).tick - marks.get(0).tick - 90) <= 1;
            log("recording markers in the buffer clip: {} at {} of {} ticks -> {}", marks.size(),
                    marks.stream().map(m -> m.name + "@" + m.tick).toList(), clipMeta.durationTicks,
                    lastOk && spacingOk && marks.size() <= 2 ? "OK" : "FAIL");
        } catch (Throwable t) {
            JustReplay.LOGGER.error("SELFTEST FAIL buffer clip", t);
            clip = null;
        }
    }

    private static long folderSize(Path dir) throws java.io.IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return files.mapToLong(f -> f.toFile().length()).sum();
        }
    }

    /**
     * Hidden armor: the player actor's helmet is hidden and its chestplate kept, the armor layer skipped at least one
     * piece while the actors were drawn, the camera player is never touched, and the global option hides every slot.
     */
    private static void checkHiddenArmor(Minecraft mc, ReplaySession s) {
        Entity notch = null;
        for (ReplayProject.ActorData a : s.project().actors) {
            if (a.isPlayer() && "Notch".equals(a.name)) {
                notch = s.actors().entityOf(a);
                break;
            }
        }
        if (notch == null) {
            log("hidden armor: player actor missing -> FAIL");
            return;
        }
        int mask = com.vyrriox.justreplay.editor.ArmorSlots.hidden(notch);
        boolean helmet = com.vyrriox.justreplay.editor.ArmorSlots.hides(notch, net.minecraft.world.entity.EquipmentSlot.HEAD);
        boolean chest = com.vyrriox.justreplay.editor.ArmorSlots.hides(notch, net.minecraft.world.entity.EquipmentSlot.CHEST);
        boolean worn = notch instanceof net.minecraft.world.entity.LivingEntity living
                && !living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        int skipped = com.vyrriox.justreplay.editor.ArmorSlots.skipped() - armorSkippedBefore;
        // The head item layer skips a hidden head on every frame by itself: the armor layer must have skipped too.
        int layer = com.vyrriox.justreplay.editor.ArmorSlots.armorLayerSkipped() - armorLayerSkippedBefore;
        log("hidden armor: actor mask {} helmet hidden {} chestplate hidden {} helmet still worn {} -> {}, pieces skipped {} of which"
                        + " by the armor layer {} -> {}",
                mask, helmet, chest, worn, mask == com.vyrriox.justreplay.editor.ArmorSlots.HEAD && helmet && !chest && worn ? "OK" : "FAIL",
                skipped, layer, layer > 0 ? "OK" : "FAIL");
        int camera = mc.player == null ? 0 : com.vyrriox.justreplay.editor.ArmorSlots.hidden(mc.player);
        log("hidden armor: camera player mask {} -> {}", camera, camera == 0 ? "OK" : "FAIL");
        boolean before = com.vyrriox.justreplay.config.JRConfig.HIDE_PLAYER_ARMOR.get();
        int all;
        try {
            com.vyrriox.justreplay.config.JRConfig.HIDE_PLAYER_ARMOR.set(true);
            all = com.vyrriox.justreplay.editor.ArmorSlots.hidden(notch);
        } finally {
            com.vyrriox.justreplay.config.JRConfig.HIDE_PLAYER_ARMOR.set(before);
        }
        log("hidden armor: global option mask {} -> {}", all, all == com.vyrriox.justreplay.editor.ArmorSlots.ALL ? "OK" : "FAIL");
        checkHiddenArmourersSkins(mc, notch);
    }

    /**
     * Armourer's Workshop, when it runs: the hidden helmet leaves the armor it collected for the player actor and the
     * chestplate stays; the global option takes the chestplate too at the next refresh, without the actor ticking,
     * and gives it back; the wardrobe collector is reached; wardrobe skins follow the slots they cover.
     */
    private static void checkHiddenArmourersSkins(Minecraft mc, Entity notch) {
        if (!com.vyrriox.justreplay.compat.Integrations.ARMOURERS_WORKSHOP.on()
                || !(notch instanceof net.minecraft.world.entity.LivingEntity living)) {
            return;
        }
        try {
            net.minecraft.world.item.ItemStack helmet = living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            net.minecraft.world.item.ItemStack chest = living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            java.util.List<net.minecraft.world.item.ItemStack> slots = com.vyrriox.justreplay.compat.ArmourersHiddenArmor.collectedArmor(notch, true);
            boolean noHelmet = slots != null && !holds(slots, helmet);
            boolean chestKept = slots != null && holds(slots, chest);
            log("armourers hidden armor: collected {} without the helmet {} with the chestplate {} -> {}",
                    slots == null ? "nothing" : slots.size() + " pieces", noHelmet, chestKept, noHelmet && chestKept ? "OK" : "FAIL");
            boolean before = com.vyrriox.justreplay.config.JRConfig.HIDE_PLAYER_ARMOR.get();
            boolean chestHidden;
            try {
                com.vyrriox.justreplay.config.JRConfig.HIDE_PLAYER_ARMOR.set(true);
                com.vyrriox.justreplay.compat.ArmourersHiddenArmor.tick(mc);
                java.util.List<net.minecraft.world.item.ItemStack> all = com.vyrriox.justreplay.compat.ArmourersHiddenArmor.collectedArmor(notch, false);
                chestHidden = all != null && !holds(all, chest);
            } finally {
                com.vyrriox.justreplay.config.JRConfig.HIDE_PLAYER_ARMOR.set(before);
            }
            com.vyrriox.justreplay.compat.ArmourersHiddenArmor.tick(mc);
            java.util.List<net.minecraft.world.item.ItemStack> back = com.vyrriox.justreplay.compat.ArmourersHiddenArmor.collectedArmor(notch, false);
            boolean chestBack = back != null && holds(back, chest);
            log("armourers hidden armor: global option takes the chestplate {} and gives it back {} -> {}",
                    chestHidden, chestBack, chestHidden && chestBack ? "OK" : "FAIL");
            String cover = com.vyrriox.justreplay.compat.ArmourersHiddenArmor.wardrobeCoverCheck();
            log("armourers hidden armor: wardrobe skins for a hidden head then chest {} -> {}", cover,
                    "hkkkkhk/khhkkhk".equals(cover) ? "OK" : "FAIL");
            int calls = com.vyrriox.justreplay.compat.ArmourersHiddenArmor.wardrobeCalls();
            log("armourers hidden armor: wardrobe collections seen {} -> {}", calls, calls > 0 ? "OK" : "FAIL");
        } catch (Throwable t) {
            log("armourers hidden armor: {} -> FAIL", t.toString());
        }
    }

    /** True when {@code stacks} holds this very stack. */
    private static boolean holds(java.util.List<net.minecraft.world.item.ItemStack> stacks, net.minecraft.world.item.ItemStack stack) {
        for (net.minecraft.world.item.ItemStack s : stacks) {
            if (s == stack) {
                return true;
            }
        }
        return false;
    }

    /**
     * World tab and Entities tab visuals: a chosen particle type is not created while another is, "None" creates
     * nothing, a recorded mob gets an outline, every entity of another recorded mob's type is hidden (counted over
     * the next ticks), then night vision and no fog for the picture.
     */
    private static void checkVisuals(Minecraft mc, ReplaySession s) {
        com.vyrriox.justreplay.editor.WorldSettings w = s.project().world;
        Vec3 at = mc.player.position().add(0, 1.5, 0);
        w.particles = com.vyrriox.justreplay.editor.WorldSettings.PARTICLES_CHOSEN;
        w.hiddenParticles = new java.util.ArrayList<>(java.util.List.of("minecraft:flame"));
        int hiddenBefore = com.vyrriox.justreplay.editor.Visuals.hiddenParticles();
        boolean flameHidden = mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.FLAME, at.x, at.y, at.z, 0, 0, 0) == null;
        boolean smokeShown = mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, at.x, at.y, at.z, 0, 0, 0) != null;
        w.particles = com.vyrriox.justreplay.editor.WorldSettings.PARTICLES_NONE;
        boolean noneHidden = mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, at.x, at.y, at.z, 0, 0, 0) == null;
        int counted = com.vyrriox.justreplay.editor.Visuals.hiddenParticles() - hiddenBefore;
        log("visuals: chosen type hidden {} other type shown {} none hides all {} counted {} -> {}", flameHidden, smokeShown, noneHidden,
                counted, flameHidden && smokeShown && noneHidden && counted >= 2 ? "OK" : "FAIL");
        w.particles = com.vyrriox.justreplay.editor.WorldSettings.PARTICLES_ALL;
        Entity typed = null;
        Entity outlined = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || com.vyrriox.justreplay.editor.ActorManager.isActorEntity(e)
                    || !(e instanceof net.minecraft.world.entity.LivingEntity) || e instanceof net.minecraft.world.entity.player.Player) {
                continue;
            }
            if (typed == null) {
                typed = e;
            } else if (outlined == null && e.getType() != typed.getType()) {
                outlined = e;
            }
        }
        if (outlined != null) {
            ReplayProject.EntityEdit glow = new ReplayProject.EntityEdit(outlined.getUUID().toString(), "test outline");
            glow.glow = true;
            s.project().entityEdits.add(glow);
            boolean glows = mc.shouldEntityAppearGlowing(outlined);
            log("visuals: outline on {} -> {}", outlined.getType().getDescriptionId(), glows ? "OK" : "FAIL");
        } else {
            log("visuals: outline skipped, no second recorded mob type");
        }
        hiddenMob = typed;
        if (typed != null) {
            s.project().toggleHiddenType(com.vyrriox.justreplay.editor.EntityEdits.typeId(typed));
        }
        skippedBefore = com.vyrriox.justreplay.editor.EntityEdits.skippedRenders();
        // Night vision: the darkest cell of the lightmap (no block light, no sky light) turns white.
        int dark = darkestLight(mc);
        w.nightVision = true;
        int lit = darkestLight(mc);
        log("visuals: night vision lightmap darkest cell {} -> {} -> {}", Integer.toHexString(dark), Integer.toHexString(lit),
                (lit & 0xFF) >= 250 && ((lit >> 8) & 0xFF) >= 250 && ((lit >> 16) & 0xFF) >= 250 ? "OK" : "FAIL");
        // No fog: the terrain fog the game sets is pushed past any view distance.
        w.fog = com.vyrriox.justreplay.editor.WorldSettings.FOG_OFF;
        net.minecraft.client.renderer.FogRenderer.setupFog(mc.gameRenderer.getMainCamera(), net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN,
                mc.gameRenderer.getRenderDistance(), false, 1.0F);
        float start = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        log("visuals: fog off starts at {} -> {}", start, start >= com.vyrriox.justreplay.editor.WorldSettings.NO_FOG ? "OK" : "FAIL");
    }

    /** Recomputes the lightmap and reads its darkest cell, as ABGR. */
    private static int darkestLight(Minecraft mc) {
        net.minecraft.client.renderer.LightTexture light = mc.gameRenderer.lightTexture();
        ((com.vyrriox.justreplay.mixin.LightTextureAccessor) light).justreplay$setUpdateLightTexture(true);
        light.updateLightTexture(1.0F);
        return ((com.vyrriox.justreplay.mixin.LightTextureAccessor) light).justreplay$lightPixels().getPixelRGBA(0, 0);
    }

    /** The two markers placed with the recording marker key arrive on the timeline at the first opening. */
    /**
     * Compat safety net, at startup: the integration {@code -Djustreplay.selftestFailCompat} names was turned off by its
     * own guard before any of its hooks ran, with a single warning, and the startup line lists it as failed.
     */
    private static void checkForcedFailureAtStartup() {
        String id = com.vyrriox.justreplay.compat.Integrations.forcedFailureId();
        if (id == null) {
            log("compat safety net: no integration forced off (-D{} unset or none), case skipped",
                    com.vyrriox.justreplay.compat.Integration.FAIL_PROPERTY);
            return;
        }
        com.vyrriox.justreplay.compat.Integration forced = com.vyrriox.justreplay.compat.Integrations.forcedFailure();
        if (forced == null) {
            log("compat safety net: Just Replay has no integration with {} on this version -> FAIL", id);
            return;
        }
        String line = com.vyrriox.justreplay.compat.Integrations.startupLine();
        boolean listed = line.contains("; failed: " + forced.name() + " (in " + com.vyrriox.justreplay.compat.Integration.FORCED_HOOK + ");");
        boolean off = !forced.on() && forced.state() == com.vyrriox.justreplay.compat.Integration.State.FAILED
                && com.vyrriox.justreplay.compat.Integration.FORCED_HOOK.equals(forced.failedHook());
        int warnings = com.vyrriox.justreplay.compat.Integrations.forcedFailureWarnings();
        log("compat safety net: {} turned off at startup ({}), {} warning(s), listed as failed in the startup line {} -> {}",
                forced.name(), forced.state().label(), warnings, listed, off && listed && warnings == 1 ? "OK" : "FAIL");
    }

    /**
     * Compat safety net, during the replay: the hooks of the integration forced off fire as a broken one's would, on
     * every kind of guard, many times. None may run, none may warn again.
     */
    private static void fireForcedFailureHooks(ReplaySession s) {
        com.vyrriox.justreplay.compat.Integration forced = com.vyrriox.justreplay.compat.Integrations.forcedFailure();
        if (forced == null) {
            return;
        }
        int[] ran = {0};
        for (int i = 0; i < 100; i++) {
            forced.run("self test hook", () -> {
                ran[0]++;
                throw new IllegalStateException("self test hook");
            });
            if (forced.test("self test check", () -> ++ran[0] > 0)) {
                ran[0]++;
            }
            forced.cleanup("self test cleanup", () -> {
                throw new NoClassDefFoundError("justreplay/selftest/Missing");
            });
        }
        forcedCalls = 300;
        forcedRan = ran[0];
        forcedCursor = s.playback().cursor();
    }

    /** Compat safety net, once the replay played on: still one warning, no hook ran, the cursor moved. */
    private static void checkForcedFailureDuringReplay(ReplaySession s) {
        com.vyrriox.justreplay.compat.Integration forced = com.vyrriox.justreplay.compat.Integrations.forcedFailure();
        if (forced == null || forcedCursor < 0) {
            return;
        }
        int warnings = com.vyrriox.justreplay.compat.Integrations.forcedFailureWarnings();
        boolean playing = ReplaySession.isRunning() && s.playback().cursor() > 20;
        log("compat safety net: {} hook calls on {} during the replay, {} ran, {} warning(s), replay playing on at tick {} -> {}",
                forcedCalls, forced.name(), forcedRan, warnings, s.playback().cursor(),
                forcedRan == 0 && warnings == 1 && !forced.on() && playing ? "OK" : "FAIL");
    }

    /**
     * The unreadable packet of the recording (tick 70) was counted as such, and the replay went on past it up to the
     * tick it was sent to (150). It may sit there: a resource reload pauses the game for a moment after the jump.
     */
    private static void checkUnreadableCounted(ReplaySession s) {
        long read = s.playback().skipped().countOf(com.vyrriox.justreplay.record.SkippedPackets.Stage.READ, UNREADABLE_TYPE);
        int cursor = s.playback().cursor();
        log("unreadable packet: {} x '{} could not be read' so far, replay playing on at tick {} -> {}", read, UNREADABLE_TYPE,
                cursor, unreadableWritten && read >= 1 && cursor >= 150 ? "OK" : "FAIL");
    }

    /** The line the end of the replay logs names the unreadable packet ("Replay: N packets skipped (...)"). */
    private static void checkSkippedLine(ReplaySession s) {
        String summary = s.playback().skipped().summary();
        boolean listed = summary != null && summary.startsWith("Replay: ")
                && summary.contains(" x " + UNREADABLE_TYPE + " " + com.vyrriox.justreplay.record.SkippedPackets.Stage.READ.label());
        log("skipped packets line at the end of the replay: [{}] -> {}", summary, listed ? "OK" : "FAIL");
    }

    /** Distance from the camera to an entity, plus 1000 when it is not within 45 degrees of the view. */
    private static double viewScore(Vec3 eye, Vec3 look, Entity e) {
        Vec3 to = e.position().add(0, e.getBbHeight() / 2.0, 0).subtract(eye);
        double d = to.length();
        boolean ahead = d < 1.0E-3 || to.scale(1.0 / d).dot(look) > Math.cos(Math.toRadians(45));
        return d + (ahead ? 0 : 1000);
    }

    /** One line per marker: every saved field, so two lists compare field by field. */
    private static java.util.List<String> describeMarkers(java.util.List<com.vyrriox.justreplay.editor.Marker> markers) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (com.vyrriox.justreplay.editor.Marker m : markers) {
            out.add(m.id + " " + m.type + " '" + m.name + "' " + m.tick + ".." + m.end
                    + String.format(java.util.Locale.ROOT, " %.3f %.3f %.3f [%s]", m.x, m.y, m.z, m.dimension)
                    + " target=" + m.target + " (" + m.targetName + ") #" + Integer.toHexString(m.color) + " note='" + m.note.replace("\n", "|") + "'");
        }
        return out;
    }

    private static void checkRecordingMarkers(ReplaySession s) {
        java.util.List<com.vyrriox.justreplay.editor.Marker> marks = s.project().markers;
        boolean names = marks.size() == 2 && "Marker 1".equals(marks.get(0).name) && "Marker 2".equals(marks.get(1).name);
        boolean ticks = names && marks.get(0).tick >= 59 && marks.get(0).tick <= 64
                && Math.abs(marks.get(1).tick - marks.get(0).tick - 90) <= 1;
        log("recording markers on the timeline: {} -> {}", marks.stream().map(m -> m.name + "@" + m.tick).toList(),
                names && ticks && s.project().recordingMarkersImported ? "OK" : "FAIL");
    }

    /**
     * Opens the replay with a mod that is not installed written into its list: the window that lists changed mods
     * must show it among the missing ones, then "Open anyway" opens the replay.
     */
    private static void openThroughModWarning(Minecraft mc, Path dir) {
        com.vyrriox.justreplay.format.ReplayMeta meta = ReplayStorage.readMeta(dir);
        if (meta == null) {
            log("mods changed window: no meta.json -> FAIL");
            ReplaySession.open(dir, mc.screen);
            return;
        }
        meta.mods.add("justreplay_selftest_missing@1.0");
        ReplayStorage.writeMeta(dir, meta);
        boolean warn = com.vyrriox.justreplay.config.JRConfig.WARN_MOD_CHANGES.get();
        com.vyrriox.justreplay.config.JRConfig.WARN_MOD_CHANGES.set(true);
        try {
            ReplaySession.open(dir, mc.screen);
        } finally {
            com.vyrriox.justreplay.config.JRConfig.WARN_MOD_CHANGES.set(warn);
        }
        if (mc.screen instanceof com.vyrriox.justreplay.gui.ModsChangedScreen window) {
            boolean listed = !window.mods().missing().isEmpty()
                    && "justreplay_selftest_missing".equals(window.mods().missing().get(0).id());
            log("mods changed window: shown, fake mod listed first {} ({} differences) -> {}", listed, window.mods().size(),
                    listed ? "OK" : "FAIL");
            window.openAnyway();
        } else {
            log("mods changed window: not shown, screen {} -> FAIL", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
        }
    }

    private static final java.util.UUID SERVER_PACK_ID = com.vyrriox.justreplay.format.ServerPackState.idOf("http://127.0.0.1:9/justreplay-selftest-pack.zip");
    private static final String SERVER_PACK_KEY = "justreplay.selftest.server_pack";
    /** 0 before the push, 1 pushed, 2 checked in the replay. */
    private static int serverPackState;
    private static int serverPackTicks;

    /**
     * Before the recording: a small pack (one translation) in the game's cache of server packs, then the integrated
     * server pushes it. The prompt the game shows for it is closed: the recording player never loads it, the replay must.
     */
    private static void pushServerPack(Minecraft mc) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
                int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
                zip.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
                zip.write(("{\"pack\": {\"pack_format\": " + format + ", \"description\": \"Just Replay self test\"}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("assets/justreplay/lang/en_us.json"));
                zip.write(("{\"" + SERVER_PACK_KEY + "\": \"applied\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            byte[] data = bytes.toByteArray();
            String sha1 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
            // 1.20.1 names a cached server pack by the SHA-1 of its address.
            String address = "http://127.0.0.1:9/justreplay-selftest-pack.zip";
            String addressSha1 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                    .digest(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Path file = mc.gameDirectory.toPath().resolve("server-resource-packs").resolve(addressSha1);
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> server.getPlayerList().getPlayers().forEach(player -> player.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundResourcePackPacket(address, sha1, false, null))));
            com.vyrriox.justreplay.config.JRConfig.SERVER_RESOURCE_PACKS.set(com.vyrriox.justreplay.config.JRConfig.ServerResourcePacks.CACHED);
            serverPackState = 1;
            log("server resource pack pushed by the integrated server ({} bytes, cached as {})", data.length, sha1);
        } catch (Exception e) {
            log("server resource pack: cannot prepare it ({}) -> FAIL", e.toString());
            serverPackState = 2;
        }
    }

    /** Closes the pack prompt of the live game, then waits in the replay until the pack's translation is there. */
    private static void pollServerPack(Minecraft mc) {
        if (serverPackState != 1) {
            return;
        }
        if (!ReplaySession.isRunning()) {
            if (mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen) {
                log("server resource pack prompt closed without loading the pack");
                mc.setScreen(null);
            }
            return;
        }
        boolean applied = com.vyrriox.justreplay.replay.ReplayPacks.applied().stream().anyMatch(pack -> pack.id().equals(SERVER_PACK_ID));
        boolean loaded = "applied".equals(net.minecraft.client.resources.language.I18n.get(SERVER_PACK_KEY));
        if (applied && loaded && mc.getOverlay() == null) {
            serverPackState = 2;
            log("server resource pack in the replay: applied from the cache, translation loaded after {} ticks -> OK", serverPackTicks);
        } else if (++serverPackTicks > 1200) {
            serverPackState = 2;
            log("server resource pack in the replay: applied {} translation loaded {} overlay {} -> FAIL", applied, loaded, mc.getOverlay());
        }
    }

    /** See {@link com.vyrriox.justreplay.format.RegistryIdsBackend#selfCheck}; the replay gets its own ids back after it. */
    private static void checkRegistryCompletion(ReplaySession s) {
        try {
            com.vyrriox.justreplay.platform.ClientPlatform.get().registryIds().selfCheck(SelfTest::log);
        } catch (Throwable t) {
            log("registry completion check failed: {} -> FAIL", t.toString());
        } finally {
            if (s != null) {
                com.vyrriox.justreplay.format.RegistryIds.apply(s.dir());
            }
        }
    }

    /** Optional mods of the test pack: content placed before the recording and their replay state. */
    private static final class Mods {
        private Mods() {
        }

        static boolean loaded(String modId) {
            // Mekanism is only a test scene, not an integration.
            return com.vyrriox.justreplay.platform.Platform.get().isModLoaded(modId)
                    && ("mekanism".equals(modId) || com.vyrriox.justreplay.compat.CompatTargets.isSupported(modId));
        }

        /**
         * The Immersive Vehicles car the self test places: the item named by {@code -Djustreplay.selftestVehicle} (an
         * item id, or the start of one, with or without {@code mts:}; Gradle {@code -PselftestVehicle=...}), else the
         * Mustang of the official content pack, else the first vehicle of any installed content pack.
         */
        static net.minecraft.world.item.Item vehicleItem() {
            String wanted = System.getProperty("justreplay.selftestVehicle", "");
            java.util.List<net.minecraft.world.item.Item> items = net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
                    .filter(i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getNamespace().equals("mts"))
                    .toList();
            if (!wanted.isEmpty()) {
                return items.stream().filter(i -> {
                    net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i);
                    return key.toString().startsWith(wanted) || key.getPath().startsWith(wanted);
                }).findFirst().orElse(null);
            }
            return items.stream()
                    .filter(i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath().startsWith("mtsofficialpack.fordmustang69"))
                    .findFirst().or(() -> items.stream().filter(Mods::isVehicleItem).findFirst()).orElse(null);
        }

        /** True for an Immersive Vehicles item that places a vehicle: the item it wraps is an {@code ItemVehicle}. */
        static boolean isVehicleItem(net.minecraft.world.item.Item item) {
            try {
                Class<?> vehicle = Class.forName("minecrafttransportsimulator.items.instances.ItemVehicle", false,
                        item.getClass().getClassLoader());
                return vehicle.isInstance(item.getClass().getMethod("getWrappedItem").invoke(item));
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }

        /** Immersive Vehicles: places the car of {@link #vehicleItem()} at an offset from the player. */
        static void placeVehicle(Minecraft mc, LocalPlayer p, int dx, int dz) {
            if (!loaded("mts")) {
                return;
            }
            net.minecraft.world.item.Item car = vehicleItem();
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            if (car == null || server == null) {
                log("vehicles: no car item (wanted '{}') or no server, nothing placed",
                        System.getProperty("justreplay.selftestVehicle", ""));
                return;
            }
            java.util.UUID id = p.getUUID();
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                if (sp == null) {
                    return;
                }
                net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(car);
                net.minecraft.world.item.ItemStack held = sp.getMainHandItem();
                sp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                net.minecraft.core.BlockPos ground = sp.blockPosition().below().offset(dx, 0, dz);
                net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(ground), net.minecraft.core.Direction.UP, ground, false);
                net.minecraft.world.InteractionResult result = stack.useOn(new net.minecraft.world.item.context.UseOnContext(
                        sp, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
                sp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
                log("vehicles: placed {} at {}: {}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(car), ground, result);
            });
        }

        /**
         * Immersive Vehicles: the car placed before the recording must have its data in the replay (it only comes from
         * the snapshot), and the replay's own draw entity must follow the camera player. Skipped when the mod is absent.
         */
        static void checkVehicles(Minecraft mc, String label) {
            if (!loaded("mts")) {
                log("vehicles {}: skipped, mod absent", label);
                return;
            }
            if (mc.level == null) {
                log("vehicles {}: skipped, no level", label);
                return;
            }
            int vehicles = 0;
            int withData = 0;
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (com.vyrriox.justreplay.compat.ImmersiveVehiclesCompat.isVehicle(e)) {
                    log("vehicles {}: {} id={} uuid={} at {}", label, e.getType(), e.getId(), e.getUUID(), e.blockPosition());
                    vehicles++;
                    withData += com.vyrriox.justreplay.compat.ImmersiveVehiclesCompat.hasData(e) ? 1 : 0;
                }
            }
            net.minecraft.world.entity.Entity draw = com.vyrriox.justreplay.compat.ImmersiveVehiclesCompat.replayForwarder();
            boolean drawing = draw != null && !draw.isRemoved() && draw.level() == mc.level;
            log("vehicles {}: vehicles={} with data={} draw entity={} -> {}", label, vehicles, withData, drawing,
                    vehicles > 0 && withData == vehicles && drawing ? "OK" : "FAIL");
        }

        /** Turns the replay camera towards the first Immersive Vehicles vehicle, for a screenshot of it. */
        static void faceVehicle(Minecraft mc) {
            if (!loaded("mts") || mc.level == null || mc.player == null) {
                return;
            }
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (com.vyrriox.justreplay.compat.ImmersiveVehiclesCompat.isVehicle(e)) {
                    mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.position());
                    log("vehicles: camera turned to {} at {}", e.getType(), e.blockPosition());
                    return;
                }
            }
        }

        /** Makes the test video before the recording starts (FFmpeg takes a moment). */
        static void prepare(Minecraft mc) {
            if (loaded("waterframes")) {
                testVideo(mc);
            }
        }

        /**
         * A ring worn before the recording starts: it must reach the snapshot, the actor and every checkpoint. Given
         * before the server catch-up that precedes the recording, so a slow server cannot run it after the start.
         */
        static void giveRing(Minecraft mc) {
            if (loaded("curios")) {
                net.minecraft.world.item.Item ring = ringItem();
                if (ring == null) {
                    log("curios: no ring item in the pack, check skipped");
                } else {
                    curiosGiven = true;
                    mc.player.connection.sendCommand("curios add ring @s 1");
                    mc.player.connection.sendCommand("curios replace ring 0 @s with "
                            + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ring));
                }
            }
        }

        private static net.minecraft.world.item.Item ringItem() {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                            new net.minecraft.resources.ResourceLocation("curios", "ring")))
                    .flatMap(tag -> tag.stream().findFirst()).map(net.minecraft.core.Holder::value).orElse(null);
        }

        /**
         * Curios alone gives no item the ring tag and the player no ring slot. Without a mod supplying them, a datapack
         * written into the test world (deleted with it at the next run) tags a vanilla item and gives the player the slot.
         */
        static void seedRing(Minecraft mc) {
            net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
            if (!loaded("curios") || ringItem() != null || server == null) {
                return;
            }
            Path pack = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR).resolve("justreplay-selftest");
            try {
                Path tag = pack.resolve("data/curios/tags/items/ring.json");
                Path entities = pack.resolve("data/justreplay/curios/entities/selftest.json");
                Files.createDirectories(tag.getParent());
                Files.createDirectories(entities.getParent());
                Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"Self test ring\"}}");
                Files.writeString(tag, "{\"replace\":false,\"values\":[\"minecraft:gold_nugget\"]}");
                Files.writeString(entities, "{\"entities\":[\"minecraft:player\"],\"slots\":[\"ring\"]}");
            } catch (Exception e) {
                log("curios: cannot write the ring datapack: {}", e.toString());
                return;
            }
            ringWaits = 480;
            log("curios: no ring item in the pack, using the test datapack");
            mc.player.connection.sendCommand("reload");
        }

        /**
         * True while the ring is not ready, for two minutes at most (a reload is slow in a big pack). Curios' reload
         * keeps an inventory without handlers
         * empty (its readTag returns on an empty list), and the player joined before the datapack existed. Once the
         * reload has reached the client, the server rebuilds the handlers of the player and a second reload syncs them.
         */
        static boolean ringPending(Minecraft mc) {
            if (ringWaits <= 0) {
                return false;
            }
            ringWaits--;
            if (ringItem() == null) {
                return ringWaits > 0;
            }
            net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
            if (!ringRebuilt && server != null) {
                ringRebuilt = true;
                java.util.UUID id = mc.player.getUUID();
                server.execute(() -> {
                    com.vyrriox.justreplay.compat.CuriosCompat.rebuildSlots(server.getPlayerList().getPlayer(id));
                    // Items put on before this reload is over are emptied by it (seen on Forge 1.20.1): wait for its end.
                    server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((done, error) -> ringReloaded = true);
                });
                return true;
            }
            if (server != null && !ringReloaded) {
                return ringWaits > 0;
            }
            if (ringSettle < 20) {
                // The slots of the reload reach the client a moment after it ends on the server.
                ringSettle++;
                return ringWaits > 0;
            }
            // Other mods give the player slots of their own: the ring slot itself must be there.
            int slots = com.vyrriox.justreplay.compat.CuriosCompat.slotTypes(mc.player);
            boolean ring = com.vyrriox.justreplay.compat.CuriosCompat.hasSlotType(mc.player, "ring");
            if (ring || ringWaits <= 0) {
                log("curios: slot types on the player before recording={}, ring slot {} -> {}", slots, ring ? "there" : "missing",
                        ring ? "OK" : "FAIL");
                ringWaits = 0;
                return false;
            }
            return true;
        }

        /**
         * What the MCP observation must list on the recording player beyond its vanilla slots: the Curios ring given
         * above, then the items of the other installed mods ({@link SelfTestModState}).
         */
        static java.util.List<String> mcpWorn() {
            java.util.List<String> out = new java.util.ArrayList<>();
            net.minecraft.world.item.Item ring = ringItem();
            if (loaded("curios") && curiosGiven && ring != null) {
                out.add("curios:ring=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ring));
            }
            out.addAll(SelfTestModState.mcpWorn());
            return out;
        }

        /**
         * Curios worn by the recording player: the ring, and what the mod state probes put in Curios slots. Tells a lost
         * ring apart from one the replay lost.
         */
        static void checkRecordingCurios(LocalPlayer p) {
            if (loaded("curios") && curiosGiven) {
                int worn = com.vyrriox.justreplay.compat.CuriosCompat.wornCount(p);
                int expected = 1 + SelfTestModState.curiosWorn();
                log("curios while recording: worn stacks on the player={} of {} -> {}", worn, expected, worn == expected ? "OK" : "FAIL");
            }
        }

        /** Curios worn by the recording player's actor in the replay: the ring and the curios of the mod state probes. */
        static void checkCurios(Minecraft mc, ReplaySession s, String label) {
            if (!loaded("curios") || !curiosGiven) {
                log("curios {}: skipped", label);
                return;
            }
            int worn = com.vyrriox.justreplay.compat.CuriosCompat.wornCount(s.findRecorder(mc));
            int expected = 1 + SelfTestModState.curiosWorn();
            log("curios {}: worn stacks on the actor={} of {} -> {}", label, worn, expected, worn == expected ? "OK" : "FAIL");
        }

        /** More Player Models: scales every part of the test player with the mod's own command; nothing when it is absent. */
        static void setLook(LocalPlayer p, String scale) {
            if (loaded("moreplayermodels")) {
                p.connection.sendCommand("mpm scale @s " + scale);
            }
        }

        /** The More Player Models look of a player, or null when the mod is absent. */
        static String look(Entity entity) {
            return loaded("moreplayermodels") && entity instanceof net.minecraft.world.entity.player.Player player
                    ? com.vyrriox.justreplay.compat.MorePlayerModelsCompat.lookOf(player) : null;
        }

        /** The looks must have changed while recording, or the replay checks below would pass on a default look. */
        static void checkRecordingLook() {
            if (loaded("moreplayermodels")) {
                boolean changed = lookBefore != null && lookAfter != null && lookThird != null && !lookBefore.equals(lookAfter)
                        && !lookAfter.equals(lookThird) && !lookBefore.equals(lookThird);
                log("more player models while recording: look read={} changed at ticks {} and {}={} -> {}", lookBefore != null,
                        LOOK_CHANGE_TICK, LOOK_THIRD_TICK, changed, changed ? "OK" : "FAIL");
            }
        }

        /**
         * More Player Models: the actor wears the look the recording player had at the cursor. The first one reaches the
         * replay through the snapshot alone, the later ones were sent to the recording player under its own UUID. After
         * the backward seek the mod holds a later look on disk for the actor, which must not replace the first one. The
         * camera keeps the look it came with. Skipped when the mod is absent.
         */
        static void checkLook(Minecraft mc, ReplaySession s, String label) {
            if (!loaded("moreplayermodels")) {
                log("more player models {}: skipped, mod absent", label);
                return;
            }
            int cursor = s.playback().cursor();
            if (Math.abs(cursor - LOOK_CHANGE_TICK) < 15 || Math.abs(cursor - LOOK_THIRD_TICK) < 15) {
                log("more player models {}: skipped, cursor {} too close to a change at {} or {}", label, cursor,
                        LOOK_CHANGE_TICK, LOOK_THIRD_TICK);
                return;
            }
            String which = cursor > LOOK_THIRD_TICK ? "third" : cursor > LOOK_CHANGE_TICK ? "second" : "first";
            String expected = cursor > LOOK_THIRD_TICK ? lookThird : cursor > LOOK_CHANGE_TICK ? lookAfter : lookBefore;
            String actor = look(s.findRecorder(mc));
            boolean worn = expected != null && expected.equals(actor);
            log("more player models {}: cursor {} actor wears the {} look={} -> {}", label, cursor, which, worn, worn ? "OK" : "FAIL");
            String camera = look(mc.player);
            boolean kept = cameraLook != null && cameraLook.equals(camera) && !cameraLook.equals(lookAfter);
            log("more player models {}: camera keeps its own look={} -> {}", label, kept, kept ? "OK" : "FAIL");
        }

        /** More Player Models during a replay: no request reaches the host, the editor stays closed, the web service is not asked. */
        static void checkLookGuards(Minecraft mc) {
            if (!loaded("moreplayermodels")) {
                return;
            }
            net.minecraft.network.FriendlyByteBuf bytes = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(1));
            bytes.writeByte(0);
            boolean blocked = com.vyrriox.justreplay.compat.ReplayCompat.blockServerbound(com.vyrriox.justreplay.record.Payloads
                    .makeServerbound(new net.minecraft.resources.ResourceLocation("moreplayermodels", "packets"), bytes));
            log("more player models: requests to the replay host blocked={} -> {}", blocked, blocked ? "OK" : "FAIL");
            try {
                net.minecraft.client.gui.screens.Screen editor = (net.minecraft.client.gui.screens.Screen) Class
                        .forName("noppes.mpm.client.gui.GuiMPM", true, mc.getClass().getClassLoader()).getConstructor().newInstance();
                boolean closed = JustReplayClient.cancelsScreen(editor);
                log("more player models: editor kept closed={} -> {}", closed, closed ? "OK" : "FAIL");
            } catch (ReflectiveOperationException | LinkageError e) {
                log("more player models: cannot build the editor screen ({}) -> FAIL", e.toString());
            }
            Boolean quiet = com.vyrriox.justreplay.compat.MorePlayerModelsCompat.webSyncHeld();
            log("more player models: web service left alone={} -> {}", quiet, Boolean.TRUE.equals(quiet) ? "OK" : "FAIL");
        }

        /** Jade: with the clean HUD on, its tooltip must be gone during the replay; skipped with a log line when absent. */
        static void checkJade(String label) {
            if (!loaded("jade")) {
                log("jade {}: skipped, mod absent", label);
            } else if (!com.vyrriox.justreplay.config.JRConfig.CLEAN_HUD.get()) {
                log("jade {}: skipped, clean HUD is off", label);
            } else {
                boolean hidden = com.vyrriox.justreplay.compat.JadeCompat.currentTooltip() == null;
                log("jade {}: tooltip hidden={} -> {}", label, hidden, hidden ? "OK" : "FAIL");
            }
        }

        /** Entity Culling's switch against the expected value; skipped when the mod is absent. */
        static void checkCulling(String label, boolean expected) {
            if (!loaded("entityculling")) {
                log("entityculling {}: skipped", label);
                return;
            }
            Boolean now = com.vyrriox.justreplay.compat.EntityCullingCompat.isEnabled();
            log("entityculling {}: enabled={} expected={} -> {}", label, now, expected, now != null && now == expected ? "OK" : "FAIL");
        }

        /** No entity of the replay world culled, and no entity tick skipped since the replay started. */
        static void checkCullingEntities(Minecraft mc) {
            if (!loaded("entityculling") || mc.level == null) {
                log("entityculling entities: skipped");
                return;
            }
            int culled = com.vyrriox.justreplay.compat.EntityCullingCompat.culledCount(mc.level.entitiesForRendering());
            int skipped = com.vyrriox.justreplay.compat.EntityCullingCompat.skippedEntityTicks();
            log("entityculling entities: culled={} skipped entity ticks since start={} -> {}", culled, skipped - cullSkipped,
                    culled == 0 && skipped == cullSkipped ? "OK" : "FAIL");
        }

        /** Places the modded test content around the start position, once the recording runs. */
        static void place(Minecraft mc, LocalPlayer p, net.minecraft.core.BlockPos base) {
            if (base == null) {
                return;
            }
            if (loaded("waterframes")) {
                Path video = testVideo(mc);
                if (video != null) {
                    // A looping 30 s test pattern with a running clock, muted.
                    p.connection.sendCommand(String.format("setblock %d %d %d waterframes:frame[facing=south]{url:\"%s\",active:1b,loop:1b,"
                                    + "max_x:3.0f,max_y:2.0f,volume:0,data_v:2s}", base.getX() - 2, base.getY() + 1, base.getZ() - 4,
                            video.toAbsolutePath().toString().replace('\\', '/')));
                }
            }
            if (loaded("mekanism")) {
                p.connection.sendCommand(String.format("setblock %d %d %d mekanism:metallurgic_infuser", base.getX() - 3, base.getY(), base.getZ() - 1));
            }
        }

        /** A short test video made with FFmpeg, or null. */
        static Path testVideo(Minecraft mc) {
            Path video = mc.gameDirectory.toPath().resolve("justreplay").resolve("selftest-video.mp4");
            if (Files.exists(video)) {
                return video;
            }
            try {
                Files.createDirectories(video.getParent());
                Process process = new ProcessBuilder(com.vyrriox.justreplay.config.JRConfig.FFMPEG_PATH.get(), "-y", "-f", "lavfi",
                        "-i", "testsrc=duration=30:size=320x180:rate=20", "-pix_fmt", "yuv420p", video.toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return Files.exists(video) ? video : null;
            } catch (Exception e) {
                log("cannot make the test video: {}", e.toString());
                return null;
            }
        }

        static String state(Minecraft mc, ReplaySession s) {
            return "cursor=" + s.playback().cursor() + " waterframes=[" + com.vyrriox.justreplay.compat.WaterFramesCompat.describe() + "]";
        }
    }

    private static void logLevelWeather(Minecraft mc, String label) {
        if (mc.level == null) {
            log("{}: no level", label);
            return;
        }
        log("{}: dayTime={} rain={} thunder={} raining={} recorded[{}]", label, mc.level.getDayTime(), mc.level.getRainLevel(1.0F),
                mc.level.getThunderLevel(1.0F), mc.level.isRaining(), com.vyrriox.justreplay.replay.WorldOverride.recorded());
    }

    private static void logWorld(Minecraft mc, ReplaySession s, String label) {
        int entities = 0;
        for (Entity ignored : mc.level.entitiesForRendering()) {
            entities++;
        }
        log("{}: cursor={}/{} paused={} entities={} chunks={} recorder={} camera={}", label, s.playback().cursor(),
                s.playback().duration(), s.playback().isPaused(), entities, mc.level.getChunkSource().getLoadedChunksCount(),
                s.findRecorder(mc) == null ? "missing" : s.findRecorder(mc).position(), mc.player.position());
    }
}
