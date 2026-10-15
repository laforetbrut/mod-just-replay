package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
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
    private static final Path FLAG = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("justreplay").resolve("selftest.flag");
    private static final boolean ENABLED = Boolean.getBoolean("justreplay.selftest") || Files.exists(FLAG);
    private static final String WORLD = "justreplay-selftest";

    private static int step;
    private static int wait;
    private static int settle;
    private static int speedStart;
    private static Path replay;

    private SelfTest() {
    }

    private static void log(String message, Object... args) {
        JustReplay.LOGGER.info("SELFTEST " + message, args);
    }

    /** Out of any world, on a menu (vanilla or customised title screen), with loading finished. */
    private static boolean atMenu(Minecraft mc) {
        return mc.level == null && mc.getOverlay() == null && mc.screen != null
                && !(mc.screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ProgressScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
    }

    private static void next(int delay) {
        step++;
        wait = delay;
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
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            run(mc);
        } catch (Throwable t) {
            JustReplay.LOGGER.error("SELFTEST FAIL at step {}", step, t);
            step = 100;
        }
    }

    private static void run(Minecraft mc) throws Exception {
        LocalPlayer p = mc.player;
        switch (step) {
            case 0 -> {
                if (atMenu(mc)) {
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
                                    .createWorldDimensions(), mc.screen);
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
                        next(60);
                    }
                }
            }
            case 2 -> {
                p.connection.sendCommand("summon minecraft:pig ~4 ~ ~2");
                p.connection.sendCommand("summon minecraft:villager ~-3 ~ ~3");
                p.connection.sendCommand("setblock ~2 ~ ~ minecraft:gold_block");
                // Modded content when available: an animated GeckoLib mob and a spinning Create motor.
                p.connection.sendCommand("summon mowziesmobs:foliaath ~-4 ~ ~-3");
                p.connection.sendCommand("setblock ~3 ~ ~-3 create:creative_motor[facing=up]");
                p.connection.sendCommand("setblock ~3 ~1 ~-3 create:cogwheel");
                boolean started = Recorder.startManual();
                log("record started: {}", started);
                next(5);
            }
            case 3 -> {
                // Walk in a circle for ~10 seconds, jumping and swinging.
                int t = Recorder.active() != null ? Recorder.active().tick() : 0;
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
                if (t == 100) {
                    p.connection.sendCommand("setblock ~ ~3 ~ minecraft:diamond_block");
                }
                if (t >= 200) {
                    replay = Recorder.stop();
                    log("record stopped: {} packets={}", replay, ReplayStorage.readStream(replay).size());
                    mc.level.disconnect();
                    mc.disconnect(new TitleScreen());
                    next(40);
                }
            }
            case 4 -> {
                if (atMenu(mc)) {
                    ReplaySession.open(replay, mc.screen);
                    log("replay opening");
                    next(40);
                }
            }
            case 5 -> {
                if (ReplaySession.isRunning() && p != null) {
                    next(60);
                }
            }
            case 6 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "playing");
                shot(mc, "1-playing");
                s.playback().seek(150);
                next(30);
            }
            case 7 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "after forward seek");
                shot(mc, "2-seek150");
                s.playback().seek(20);
                next(30);
            }
            case 8 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "after backward seek");
                shot(mc, "3-seek20");
                speedStart = s.playback().cursor();
                s.playback().changeSpeed(2);
                next(10);
            }
            case 9 -> {
                ReplaySession s = ReplaySession.current();
                log("speed x{}: cursor advanced {} ticks in 10 client ticks", s.playback().speed(), s.playback().cursor() - speedStart);
                s.playback().resetSpeed();
                s.playback().seek(190);
                s.playback().seek(130);
                logWorld(mc, s, "after checkpoint seek to 130");
                s.playback().seek(20);
                s.jumpToRecorder(mc);
                Vec3 c = p.position();
                ReplayProject.ActorData player = new ReplayProject.ActorData();
                player.name = "Notch";
                player.mainHand = "minecraft:diamond_sword";
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
            case 10 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "camera path + actors");
                for (ReplayProject.ActorData a : s.project().actors) {
                    Entity e = s.actors().entityOf(a);
                    log("actor {} -> {}", a.type, e == null ? "missing" : e.getClass().getSimpleName() + " @ " + e.position());
                }
                shot(mc, "4-path-actors");
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
                mc.setScreen(new EditorScreen(s));
                next(10);
            }
            case 11 -> {
                shot(mc, "5-editor");
                next(5);
            }
            case 12 -> {
                mc.setScreen(null);
                ReplaySession s = ReplaySession.current();
                s.setCameraPath(false);
                Files.deleteIfExists(ReplayStorage.exportRoot().resolve("selftest.mp4"));
                com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.set(com.vyrriox.justreplay.export.ExportResolution.FULL_HD_1080);
                var error = VideoExporter.start(s, 20, 80, "selftest");
                log("export start error={}", error == null ? "none" : error.getString());
                next(1);
            }
            case 13 -> {
                if (!VideoExporter.isActive()) {
                    next(200);
                }
            }
            case 14 -> {
                Path mp4 = ReplayStorage.exportRoot().resolve("selftest.mp4");
                log("export file exists={} size={}", Files.exists(mp4), Files.exists(mp4) ? Files.size(mp4) : 0);
                mc.level.disconnect();
                mc.disconnect(new TitleScreen());
                next(60);
            }
            case 15 -> {
                log("DONE");
                Files.deleteIfExists(FLAG);
                step = 100;
                mc.stop();
            }
            default -> {
            }
        }
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
