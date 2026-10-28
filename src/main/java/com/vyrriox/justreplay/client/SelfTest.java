package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.overlay.Overlay;
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
    private static Entity hiddenMob;
    private static Entity movedMob;
    private static int skippedBefore;
    private static int offsetBefore;
    private static int wait;
    private static int settle;
    private static int speedStart;
    private static Path replay;
    /** Start position of the recording, around which the modded test content is placed. */
    private static net.minecraft.core.BlockPos modsBase;
    private static Path clip;
    private static java.util.concurrent.CompletableFuture<com.vyrriox.justreplay.format.ReplayBuffer.Saved> clipSave;
    /** Blocks the placement steps seat and lay actors on: stairs and the head of a bed. */
    private static net.minecraft.core.BlockPos stairPos;
    private static net.minecraft.core.BlockPos bedPos;
    private static ReplayProject.ActorData placed;
    private static com.vyrriox.justreplay.editor.ActorPlacement.Spot seatSpot;
    private static int fxStep;
    /** Sounds the recording player made itself, heard again while the replay plays (see recordLocalEffect). */
    private static int localBell;
    private static int localAnvil;
    private static int replaySteps;
    private static int recorderSteps;

    /** Counts, during a replay, the sounds the self test made for its own player while recording. */
    public static void onSound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        if (!ENABLED || !ReplaySession.isRunning() || event.getSound() == null) {
            return;
        }
        String id = event.getSound().getLocation().getPath();
        if (id.equals("block.note_block.bell")) {
            localBell++;
        } else if (id.equals("block.anvil.use")) {
            localAnvil++;
        } else if (id.endsWith(".step")) {
            replaySteps++;
            // Footsteps of the recording player itself: they only exist if the recorder captured them.
            Minecraft mc = Minecraft.getInstance();
            Entity recorder = ReplaySession.current() != null ? ReplaySession.current().findRecorder(mc) : null;
            if (recorder != null && recorder.distanceToSqr(event.getSound().getX(), event.getSound().getY(), event.getSound().getZ()) < 2.25) {
                recorderSteps++;
            }
        }
    }
    private static int galleryIndex;
    private static String galleryName = "";
    /** Export settings and editor mode of the user, saved at the start and put back at the end of the test. */
    private static int savedCrf = -1;
    private static int savedFps;
    private static com.vyrriox.justreplay.export.ExportResolution savedResolution;
    private static com.vyrriox.justreplay.config.JRConfig.EditorMode savedMode;

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
            restoreUserConfig();
            step = 100;
        }
    }

    private static void run(Minecraft mc) throws Exception {
        LocalPlayer p = mc.player;
        switch (step) {
            case 0 -> {
                saveUserConfig();
                if (atMenu(mc)) {
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
                // With Emotecraft the player stands still from tick 40 to 100 to wave (moving stops an emote).
                boolean emoting = Mods.loaded("emotecraft") && t >= 40 && t < 100;
                if (t == 5) {
                    // Placed once the recording runs: a block changed in the same tick as the start could be missed.
                    Mods.place(mc, p, modsBase);
                }
                if (t == 40 && Mods.loaded("emotecraft")) {
                    p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
                    log("local emote started: {}", com.vyrriox.justreplay.compat.EmotecraftCompat.playLocal("waving"));
                }
                if (t == 60 && Mods.loaded("emotecraft")) {
                    log("local emote at recording tick 60: {}", com.vyrriox.justreplay.compat.EmotecraftCompat.describe(p));
                }
                if (!emoting) {
                    p.setYRot(p.getYRot() + 4);
                    p.setYHeadRot(p.getYRot());
                    Vec3 look = Vec3.directionFromRotation(0, p.getYRot()).scale(0.25);
                    p.setDeltaMovement(look.x, p.getDeltaMovement().y, look.z);
                }
                if (t % 40 == 0 && p.onGround() && !emoting) {
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
                if (t == 100) {
                    p.connection.sendCommand("setblock ~ ~3 ~ minecraft:diamond_block");
                }
                if (t % 40 == 0 && Recorder.capturing() != null) {
                    log("recording cost: {}", Recorder.capturing().lastCost());
                }
                if (t >= 200 && clipSave == null) {
                    clipSave = Recorder.saveBuffer();
                    log("buffer save requested: {}", clipSave != null);
                    if (clipSave == null) {
                        clipSave = java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("no buffer"));
                    }
                }
                if (t >= 200 && clipSave.isDone()) {
                    verifyClip(mc);
                    replay = Recorder.stop();
                    log("record stopped: {} packets={} stream={} bytes checkpoints={} bytes in {} files", replay,
                            ReplayStorage.readStream(replay).size(), Files.size(replay.resolve(ReplayStorage.STREAM)),
                            folderSize(replay.resolve(com.vyrriox.justreplay.format.Checkpoints.FOLDER)),
                            com.vyrriox.justreplay.format.Checkpoints.list(replay).length);
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
                log("mods while playing: {}", Mods.state(mc, s));
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
                shot(mc, "1b-paused60");
                s.playback().setPaused(false);
                s.playback().seek(150);
                next(30);
            }
            case 8 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "after forward seek");
                log("mods after forward seek: {}", Mods.state(mc, s));
                shot(mc, "2-seek150");
                s.playback().seek(20);
                next(30);
            }
            case 9 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "after backward seek");
                shot(mc, "3-seek20");
                speedStart = s.playback().cursor();
                s.playback().changeSpeed(2);
                next(10);
            }
            case 10 -> {
                ReplaySession s = ReplaySession.current();
                log("speed x{}: cursor advanced {} ticks in 10 client ticks", s.playback().speed(), s.playback().cursor() - speedStart);
                s.playback().resetSpeed();
                s.playback().seek(190);
                s.playback().seek(130);
                logWorld(mc, s, "after checkpoint seek to 130");
                Mods.logDropTheBeam("after checkpoint seek to 130");
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
                if (Mods.loaded("emotecraft")) {
                    player.actions.add(new ReplayProject.ActorAction(70, com.vyrriox.justreplay.editor.ActionType.EMOTE, 60, "waving"));
                }
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
                    if (e instanceof net.minecraft.world.entity.player.Player) {
                        log("actor {} emote at cursor {}: {}", a.name, s.playback().cursor(),
                                com.vyrriox.justreplay.compat.EmotecraftCompat.describe(e));
                    }
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
                com.vyrriox.justreplay.config.JRConfig.EDITOR_MODE.set(com.vyrriox.justreplay.config.JRConfig.EditorMode.ADVANCED);
                addBeepClip(s);
                mc.setScreen(new EditorScreen(s));
                next(10);
            }
            case 12 -> {
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
                hiddenMob = null;
                movedMob = null;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player || com.vyrriox.justreplay.editor.ActorManager.isActorEntity(e)
                            || !(e instanceof net.minecraft.world.entity.LivingEntity)) {
                        continue;
                    }
                    if (hiddenMob == null) {
                        hiddenMob = e;
                    } else if (movedMob == null) {
                        movedMob = e;
                    }
                }
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
                go(13, 5);
            }
            case 13 -> {
                mc.setScreen(null);
                ReplaySession s = ReplaySession.current();
                s.setCameraPath(false);
                Files.deleteIfExists(ReplayStorage.exportRoot().resolve("selftest.mp4"));
                com.vyrriox.justreplay.config.JRConfig.EXPORT_CRF.set(18);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_FPS.set(60);
                com.vyrriox.justreplay.config.JRConfig.EXPORT_RESOLUTION.set(com.vyrriox.justreplay.export.ExportResolution.FULL_HD_1080);
                com.vyrriox.justreplay.editor.overlay.OverlayRenderer.resetExportFrames();
                var error = VideoExporter.start(s, 20, 80, "selftest");
                log("export start error={}", error == null ? "none" : error.getString());
                next(1);
            }
            case 14 -> {
                if (!VideoExporter.isActive()) {
                    next(200);
                }
            }
            case 15 -> {
                Path mp4 = ReplayStorage.exportRoot().resolve("selftest.mp4");
                log("export file exists={} size={}", Files.exists(mp4), Files.exists(mp4) ? Files.size(mp4) : 0);
                log("export audio clips mixed={} (expected 2), audio mux log={}", com.vyrriox.justreplay.export.ExportAudio.lastMixedClips(),
                        Files.exists(ReplayStorage.exportRoot().resolve("selftest.mp4.audio.log")));
                log("mods after export: {}", Mods.state(mc, ReplaySession.current()));
                Mods.logDropTheBeam("after export");
                log("local effects heard in the replay: bell={} anvil={} footsteps={} of which the recording player's own={} -> {}", localBell,
                        localAnvil, replaySteps, recorderSteps, localBell > 0 && localAnvil > 0 && recorderSteps > 0 ? "OK" : "FAIL");
                log("export frames showing overlays: {} ({} overlays in the project)",
                        com.vyrriox.justreplay.editor.overlay.OverlayRenderer.exportFrames(), ReplaySession.current().project().overlays.size());
                mc.level.disconnect();
                mc.disconnect(new TitleScreen());
                next(60);
            }
            case 16 -> {
                if (atMenu(mc)) {
                    if (clip == null) {
                        step = 19;
                        return;
                    }
                    ReplaySession.open(clip, mc.screen);
                    log("buffer clip opening");
                    next(40);
                }
            }
            case 17 -> {
                if (ReplaySession.isRunning() && p != null) {
                    next(60);
                }
            }
            case 18 -> {
                ReplaySession s = ReplaySession.current();
                logWorld(mc, s, "buffer clip playing");
                shot(mc, "6-buffer-clip");
                s.playback().seek(Math.max(0, s.playback().duration() - 10));
                logWorld(mc, s, "buffer clip after forward seek");
                s.playback().seek(5);
                logWorld(mc, s, "buffer clip after backward seek");
                mc.level.disconnect();
                mc.disconnect(new TitleScreen());
                next(60);
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

    /** Optional mods of the test pack: content placed before the recording and their replay state. */
    private static final class Mods {
        private Mods() {
        }

        static boolean loaded(String modId) {
            return net.neoforged.fml.ModList.get().isLoaded(modId);
        }

        /** Makes the test video before the recording starts (FFmpeg takes a moment). */
        static void prepare(Minecraft mc) {
            if (loaded("waterframes")) {
                testVideo(mc);
            }
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

        /** Create's animation clock, or -1. */
        static int createTicks() {
            try {
                return (int) Class.forName("net.createmod.catnip.animation.AnimationTickHolder").getMethod("getTicks").invoke(null);
            } catch (Throwable t) {
                return -1;
            }
        }

        /** Drop the Beam's replay clock, voices, song starts and exported audio; nothing when it is absent. */
        static void logDropTheBeam(String label) {
            if (!loaded("dropthebeam")) {
                return;
            }
            log("dropthebeam {}: clock samples={} mapped tick 20={} tick 100={} voices={} song starts={} last export={}", label,
                    com.vyrriox.justreplay.compat.DropTheBeamCompat.clockSamples(),
                    com.vyrriox.justreplay.compat.DropTheBeamCompat.mappedNanos(20),
                    com.vyrriox.justreplay.compat.DropTheBeamCompat.mappedNanos(100),
                    com.vyrriox.justreplay.compat.DropTheBeamCompat.voiceCount(),
                    com.vyrriox.justreplay.compat.DropTheBeamCompat.songStartTicks().size(),
                    com.vyrriox.justreplay.compat.DropTheBeamCompat.lastExport());
        }

        static String state(Minecraft mc, ReplaySession s) {
            return "cursor=" + s.playback().cursor() + " createTicks=" + createTicks()
                    + " recorderEmote=" + com.vyrriox.justreplay.compat.EmotecraftCompat.describe(s.findRecorder(mc))
                    + " waterframes=[" + com.vyrriox.justreplay.compat.WaterFramesCompat.describe() + "]";
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
