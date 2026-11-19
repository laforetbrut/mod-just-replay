package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The integrations (CURIOS, SABLE...): ReplayCompat forwards each hook through the one it belongs to.
import static com.vyrriox.justreplay.compat.Integrations.*;

/**
 * Entry point of the optional mod integrations: the replay session, the playback and the recorder call these hooks,
 * which forward them to each integration whose mod is installed. Every integration reaches its mod by reflection
 * (or through optional mixins), so nothing here loads a class of a missing mod.
 * <p>
 * Each call into an integration goes through its {@link Integration} (see {@link Integrations}): it runs only while
 * the integration is on, and any error it throws (a {@link LinkageError} from a changed mod included) turns that
 * integration off for the rest of the game with one warning, instead of crashing the game, the recording or the
 * replay. Hooks that put back what an integration changed in its mod still run after such a failure
 * ({@link Integration#cleanup}).
 *
 * @author vyrriox
 */
public final class ReplayCompat {
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());
    private static double monotonicFrame = -1;
    private static double monotonicValue;
    private static double lastGeckoEntityFrameTime = -1;

    private ReplayCompat() {
    }

    /** Logs an integration failure once per key, then the integration keeps going without that feature. */
    static void warnOnce(String key, String message, Throwable t) {
        if (FAILED.add(key)) {
            JustReplay.LOGGER.warn("{}: {}", message, t == null ? "" : t.toString());
        }
    }

    /**
     * Logs once an error of a compat mixin that adjusts the game itself for a mod (the level renderer clock, the section
     * draw guard): that adjustment is skipped and the game keeps its own behaviour.
     */
    public static void warnOnceGame(String hook, Throwable t) {
        warnOnce("game." + hook, "Just Replay cannot adjust " + hook + " during replays, it keeps the game's behaviour", t);
    }

    // ---------------------------------------------------------------- replay clock

    /** The running replay's playback, or null outside a replay. */
    static Playback playback() {
        ReplaySession session = ReplaySession.current();
        return session != null && ReplaySession.isRunning() ? session.playback() : null;
    }

    /**
     * Animation ticks a client tick should advance during a replay: 0 while paused, several above x1 (the client never
     * ticks faster than 20 TPS, the playback plays several recorded ticks per client tick instead). -1 outside a replay.
     */
    public static int animationSteps() {
        Playback playback = playback();
        if (playback == null) {
            return -1;
        }
        if (playback.isPaused() || Minecraft.getInstance().isPaused()) {
            return 0;
        }
        return Math.max(1, Math.round(Math.max(1.0F, playback.speed())));
    }

    /** True while a replay is paused: client-side animations hold their pose. */
    public static boolean isReplayPaused() {
        Playback playback = playback();
        return playback != null && playback.isPaused();
    }

    /**
     * Value for client animation tick counters (Create's animation clock, the level renderer clock used by Flywheel)
     * during a replay: the replay cursor, so they pause, follow the speed, and show the same state after a seek or in
     * an export started anywhere. -1 outside a replay.
     */
    public static int animationTicks() {
        Playback playback = playback();
        return playback == null ? -1 : playback.cursor() % 1728000;
    }

    /**
     * Replay time of the frame being rendered, in ticks with its fraction: holds while paused, follows the speed and is
     * exact per export frame. Goes back after a backward seek. -1 outside a replay.
     */
    public static double frameClockTicks() {
        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning()) {
            return -1;
        }
        return com.vyrriox.justreplay.editor.CameraController.frameTime(session,
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
    }

    /**
     * {@link #frameClockTicks()} for animation systems that hold when their clock goes back: it adds the rises of the
     * frame clock and never decreases, a backward seek only stops counting. It starts at the wall clock, or above if an
     * earlier replay ran faster, because animations started before the replay hold their start time. -1 outside a
     * replay. Render thread only.
     */
    public static double monotonicAnimTicks() {
        double now = frameClockTicks();
        if (now < 0) {
            monotonicFrame = -1;
            return -1;
        }
        if (monotonicFrame < 0) {
            monotonicValue = Math.max(monotonicValue, com.mojang.blaze3d.Blaze3D.getTime() * 20.0);
            monotonicFrame = now;
        } else if (now > monotonicFrame) {
            monotonicValue += now - monotonicFrame;
            monotonicFrame = now;
        } else if (monotonicFrame - now > Math.max(2, playback().lastStep() + 1)) {
            // A real backward seek. Smaller drops come from resuming a pause, which replays the last step's fraction.
            monotonicFrame = now;
        }
        return monotonicValue;
    }

    /** Replaces the frame time of a GeckoLib entity during a replay, which otherwise follows its tick counter. */
    public static double geckoEntityFrameTime(double original) {
        double ticks = monotonicAnimTicks();
        if (ticks < 0) {
            return original;
        }
        lastGeckoEntityFrameTime = ticks;
        return ticks;
    }

    /** The last frame time given to a GeckoLib entity, -1 if none was: no such entity rendered yet. */
    public static double lastGeckoEntityFrameTime() {
        return lastGeckoEntityFrameTime;
    }

    // ---------------------------------------------------------------- session hooks

    /**
     * Called off the render thread when a replay is about to open, before its world is created: integrations copy or
     * prepare what the replay world needs (Distant Horizons' recorded LODs).
     */
    public static void prepareReplay(com.vyrriox.justreplay.format.ReplayMeta meta) {
        DISTANT_HORIZONS.run("prepare", () -> DistantHorizonsCompat.prepare(meta));
    }

    /** Called once the replay world has fully closed (its integrated server stopped), or after it failed to open. */
    public static void onReplayWorldClosed() {
        DISTANT_HORIZONS.cleanup("onReplayWorldClosed", DistantHorizonsCompat::onReplayWorldClosed);
    }

    /** Called once when replay playback starts. */
    public static void onReplayStart(Minecraft mc) {
        DISTANT_HORIZONS.run("onReplayStart", DistantHorizonsCompat::onReplayStart);
        BETTER_COMBAT.run("onReplayStart", BetterCombatCompat::onReplayStart);
        ENTITY_CULLING.run("onReplayStart", EntityCullingCompat::onReplayStart);
        DROP_THE_BEAM.run("onReplayStart", () -> {
            ReplaySession session = ReplaySession.current();
            DropTheBeamCompat.onReplayStart(session != null ? session.dir() : null);
        });
        IRONS_SPELLS.run("onReplayStart", IronsSpellsCompat::clearState);
    }

    /** Called every client tick, replay or not. */
    public static void clientTick(Minecraft mc) {
        if (DISTANT_HORIZONS.on() && !ReplaySession.isRunning()) {
            DISTANT_HORIZONS.run("recoverAfterInterruptedReplay", DistantHorizonsCompat::recoverAfterInterruptedReplay);
        }
        if (!sableChecked && SABLE.on()) {
            // Resolved once up front, so the detection line (or the failure) shows in the log at startup.
            sableChecked = true;
            SABLE.run("reflect", SableCompat::reflect);
        }
    }

    private static boolean sableChecked;

    /** Called every client tick of a running replay, after the playback tick. */
    public static void tick(Minecraft mc, ReplaySession session) {
        IMMERSIVE_VEHICLES.run("tick", () -> ImmersiveVehiclesCompat.tick(mc));
        WATERFRAMES.run("tick", () -> WaterFramesCompat.tick(mc, session.playback()));
        DISTANT_HORIZONS.run("tick", DistantHorizonsCompat::tick);
        DROP_THE_BEAM.run("tick", () -> DropTheBeamCompat.tick(mc, session));
        EPIC_FIGHT.run("applyPending", EpicFightCompat::applyPending);
        ENTITY_CULLING.run("tick", EntityCullingCompat::tick);
    }

    /** Called when the replay session closes. Runs for integrations that failed too: they put back what they changed. */
    public static void onReplayEnd() {
        monotonicFrame = -1;
        ENTITY_CULLING.cleanup("onReplayEnd", EntityCullingCompat::onReplayEnd);
        WATERFRAMES.cleanup("onReplayEnd", WaterFramesCompat::clear);
        DISTANT_HORIZONS.cleanup("onReplayEnd", DistantHorizonsCompat::onReplayEnd);
        DROP_THE_BEAM.cleanup("onReplayEnd", DropTheBeamCompat::onReplayEnd);
        ARMOURERS_WORKSHOP.cleanup("onReplayEnd", ArmourersWorkshopCompat::onReplayEnd);
        EPIC_FIGHT.cleanup("onReplayEnd", EpicFightCompat::onReplayEnd);
        IRONS_SPELLS.cleanup("onReplayEnd", IronsSpellsCompat::clearState);
        IMMERSIVE_VEHICLES.cleanup("onReplayEnd", ImmersiveVehiclesCompat::onReplayEnd);
    }

    /** A seek is about to rebuild the replay world from a checkpoint or from the start of the stream. */
    public static void onWorldRebuild() {
        IRONS_SPELLS.run("onWorldRebuild", IronsSpellsCompat::clearState);
    }

    /**
     * A recorded packet about to be handled at its recorded tick (seeks included): true when an integration consumed
     * it and it must not reach the client (Drop the Beam's clock packets, which belong to the recording process).
     * Called for every packet: no allocation.
     */
    public static boolean consumeRecorded(Packet<?> packet, int tick) {
        if (EPIC_FIGHT.on()) {
            try {
                // The previous packet has been handled: its Epic Fight animation can be moved to its time.
                EpicFightCompat.applyPending();
            } catch (Throwable t) {
                EPIC_FIGHT.fail("applyPending", t);
            }
        }
        if (BETTER_COMBAT.on()) {
            try {
                BetterCombatCompat.onRecorded(packet);
            } catch (Throwable t) {
                BETTER_COMBAT.fail("onRecorded", t);
            }
        }
        if (MORPH.on()) {
            try {
                if (MorphCompat.consumeRecorded(packet)) {
                    return true;
                }
            } catch (Throwable t) {
                MORPH.fail("consumeRecorded", t);
            }
        }
        if (DROP_THE_BEAM.on()) {
            try {
                return DropTheBeamCompat.consumeRecorded(packet, tick);
            } catch (Throwable t) {
                DROP_THE_BEAM.fail("consumeRecorded", t);
            }
        }
        return false;
    }

    /**
     * A recorded packet about to be handled, after the generic filters: returns the packet to handle in its place, or
     * null to drop it (Sable payloads meant for the live player only, pose timing scaled to the playback speed). A
     * failing integration leaves the packet as it was. Called for every packet: no allocation.
     */
    public static Packet<?> filterPlayback(Packet<?> packet) {
        if (SABLE.on()) {
            try {
                packet = SableCompat.filterPlayback(packet, playback());
            } catch (Throwable t) {
                SABLE.fail("filterPlayback", t);
            }
        }
        if (packet != null && CUSTOM_NPCS.on()) {
            try {
                packet = CustomNpcsCompat.filterPlayback(packet);
            } catch (Throwable t) {
                CUSTOM_NPCS.fail("filterPlayback", t);
            }
        }
        if (packet != null && EPIC_FIGHT.on()) {
            try {
                packet = EpicFightCompat.filterPlayback(packet);
            } catch (Throwable t) {
                EPIC_FIGHT.fail("filterPlayback", t);
            }
        }
        if (packet != null && IRONS_SPELLS.on()) {
            try {
                packet = IronsSpellsCompat.filterPlayback(packet);
            } catch (Throwable t) {
                IRONS_SPELLS.fail("filterPlayback", t);
            }
        }
        if (packet != null && EXPOSURE.on()) {
            try {
                packet = ExposureCompat.filterPlayback(packet);
            } catch (Throwable t) {
                EXPOSURE.fail("filterPlayback", t);
            }
        }
        if (packet != null && ACCESSORIES.on()) {
            try {
                packet = AccessoriesCompat.filterPlayback(packet);
            } catch (Throwable t) {
                ACCESSORIES.fail("filterPlayback", t);
            }
        }
        if (packet != null && JADE.on()) {
            try {
                packet = JadeCompat.filterPlayback(packet);
            } catch (Throwable t) {
                JADE.fail("filterPlayback", t);
            }
        }
        if (packet != null && IMMERSIVE_PAINTINGS.on()) {
            try {
                packet = ImmersivePaintingsCompat.filterPlayback(packet);
            } catch (Throwable t) {
                IMMERSIVE_PAINTINGS.fail("filterPlayback", t);
            }
        }
        return packet;
    }

    /** True for a chunk of a mod's far away storage area that must stay loaded whatever the camera distance (Sable plots). */
    public static boolean isDetachedChunk(int x, int z) {
        if (!SABLE.on()) {
            return false;
        }
        try {
            return SableCompat.isPlotChunk(x, z);
        } catch (Throwable t) {
            SABLE.fail("isPlotChunk", t);
            return false;
        }
    }

    /** A packet the client is about to send to the replay host: true to drop it (requests of mods with no server there). */
    public static boolean blockServerbound(Packet<?> packet) {
        return DROP_THE_BEAM.on() && DROP_THE_BEAM.test("blockServerbound", () -> DropTheBeamCompat.blockServerbound(packet))
                || PARCOOL.on() && PARCOOL.test("blockServerbound", () -> ParCoolCompat.blockServerbound(packet))
                || BETTER_COMBAT.on() && BETTER_COMBAT.test("blockServerbound", () -> BetterCombatCompat.blockServerbound(packet))
                || MORPH.on() && MORPH.test("blockServerbound", () -> MorphCompat.blockServerbound(packet))
                || CUSTOM_NPCS.on() && CUSTOM_NPCS.test("blockServerbound", () -> CustomNpcsCompat.blockServerbound(packet))
                || EPIC_FIGHT.on() && EPIC_FIGHT.test("blockServerbound", () -> EpicFightCompat.blockServerbound(packet))
                || IRONS_SPELLS.on() && IRONS_SPELLS.test("blockServerbound", () -> IronsSpellsCompat.blockServerbound(packet))
                || EXPOSURE.on() && EXPOSURE.test("blockServerbound", () -> ExposureCompat.blockServerbound(packet))
                || ACCESSORIES.on() && ACCESSORIES.test("blockServerbound", () -> AccessoriesCompat.blockServerbound(packet))
                || JADE.on() && JADE.test("blockServerbound", () -> JadeCompat.blockServerbound(packet))
                || IMMERSIVE_AIRCRAFT.on() && IMMERSIVE_AIRCRAFT.test("blockServerbound", () -> ImmersiveAircraftCompat.blockServerbound(packet))
                || IMMERSIVE_PAINTINGS.on() && IMMERSIVE_PAINTINGS.test("blockServerbound", () -> ImmersivePaintingsCompat.blockServerbound(packet))
                || IMMERSIVE_VEHICLES.on() && IMMERSIVE_VEHICLES.test("blockServerbound", () -> ImmersiveVehiclesCompat.blockServerbound(packet));
    }

    // ---------------------------------------------------------------- recording hooks

    /**
     * Notes in a recording's meta what integrations need to know about the level being recorded (the Distant Horizons
     * LOD folder of its dimension). Called every client tick of a recording, and with {@code force} when a meta is made.
     */
    public static void describeLevel(com.vyrriox.justreplay.format.ReplayMeta meta, net.minecraft.world.level.Level level,
                                     boolean force) {
        if (DISTANT_HORIZONS.on()) {
            DISTANT_HORIZONS.run("describe", () -> DistantHorizonsCompat.describe(meta, level, force));
        }
    }

    /**
     * A packet the client sends on a live connection (any thread): integrations record what the server never sends
     * back to its sender (Better Combat's attack animations).
     */
    public static void onLiveServerbound(Packet<?> packet) {
        if (BETTER_COMBAT.on()) {
            try {
                BetterCombatCompat.onLiveServerbound(packet);
            } catch (Throwable t) {
                BETTER_COMBAT.fail("onLiveServerbound", t);
            }
        }
        if (EPIC_FIGHT.on()) {
            try {
                EpicFightCompat.onLiveServerbound(packet);
            } catch (Throwable t) {
                EPIC_FIGHT.fail("onLiveServerbound", t);
            }
        }
    }

    /**
     * A captured clientbound packet about to be written into the recording (network thread): returns the packet to
     * write in its place. ParCool echoes the recording player's own actions back to it under its real UUID; they are
     * written for its actor, as another client receives them. Morph forms naming the recording player's UUID name the
     * actor's instead. The packet the client handles is never changed. Called for every packet: no allocation.
     */
    public static Packet<?> rewriteRecorded(Packet<?> packet, java.util.UUID localUuid, java.util.UUID actorUuid) {
        if (PARCOOL.on()) {
            try {
                packet = ParCoolCompat.rewriteRecorded(packet, localUuid, actorUuid);
            } catch (Throwable t) {
                PARCOOL.fail("rewriteRecorded", t);
            }
        }
        if (MORPH.on()) {
            try {
                packet = MorphCompat.rewriteRecorded(packet, localUuid, actorUuid);
            } catch (Throwable t) {
                MORPH.fail("rewriteRecorded", t);
            }
        }
        if (IRONS_SPELLS.on()) {
            try {
                packet = IronsSpellsCompat.rewriteRecorded(packet, localUuid, actorUuid);
            } catch (Throwable t) {
                IRONS_SPELLS.fail("rewriteRecorded", t);
            }
        }
        return packet;
    }

    /** A live packet that is not written to the recording, because its replay would drop it anyway. */
    public static boolean skipRecording(Packet<?> packet) {
        if (!IRONS_SPELLS.on()) {
            return false;
        }
        try {
            return IronsSpellsCompat.isLocalOnly(packet);
        } catch (Throwable t) {
            IRONS_SPELLS.fail("isLocalOnly", t);
            return false;
        }
    }

    /**
     * True for a mod packet a snapshot must write again because the server sends it only once per connection (Create's
     * train network).
     */
    public static boolean isPersistentModPacket(Packet<?> packet) {
        if (!CREATE.on()) {
            return false;
        }
        try {
            return CreateCompat.isPersistent(packet);
        } catch (Throwable t) {
            CREATE.fail("isPersistent", t);
            return false;
        }
    }

    /** Polls the local player's emote for a recorded tick (see {@link EmotecraftCompat.LocalEmotes}). */
    public static void pollLocalEmotes(EmotecraftCompat.LocalEmotes emotes, net.minecraft.world.entity.Entity player,
                                       java.util.UUID actor, boolean respawned, java.util.function.Consumer<Packet<?>> out) {
        if (EMOTECRAFT.on()) {
            EMOTECRAFT.run("pollLocalEmotes", () -> emotes.poll(player, actor, respawned, out));
        }
    }

    /** Mod definitions the entity state depends on (snapshots and checkpoints), written before any entity. */
    public static void writeDefinitions(java.util.function.Consumer<Packet<?>> out) {
        CURIOS.run("writeDefinitions", () -> CuriosCompat.writeDefinitions(out));
    }

    /**
     * Mod animation state a player is in now (snapshots and checkpoints), written as another client receives it, for
     * the player with this UUID in the replay (the actor for the recording player).
     */
    public static void writePlayerState(net.minecraft.world.entity.player.Player player, java.util.UUID target,
                                        java.util.function.Consumer<Packet<?>> out) {
        EMOTECRAFT.run("writeCurrent", () -> EmotecraftCompat.writeCurrent(player, target, out));
        PARCOOL.run("writeCurrent", () -> ParCoolCompat.writeCurrent(player, target, out));
        MORPH.run("writeCurrent", () -> MorphCompat.writeCurrent(player, target, out));
        PEHKUI.run("writeCurrent", () -> PehkuiCompat.writeCurrent(player, out));
        ARMOURERS_WORKSHOP.run("writeCurrent", () -> ArmourersWorkshopCompat.writeCurrent(player, out));
        // Keyed by entity id, which the actor shares with the recording player.
        EPIC_FIGHT.run("writeCurrent", () -> EpicFightCompat.writeCurrent(player, out));
        CURIOS.run("writeCurrent", () -> CuriosCompat.writeCurrent(player, out));
        ACCESSORIES.run("writeCurrent", () -> AccessoriesCompat.writeCurrent(player, out));
        IRONS_SPELLS.run("writeCurrent", () -> IronsSpellsCompat.writeCurrent(player, out));
    }

    /** Mod state of the non-player entities of a snapshot or checkpoint, written after they spawn (Pehkui scales). */
    public static void writeEntityState(Iterable<net.minecraft.world.entity.Entity> entities,
                                        java.util.function.Consumer<Packet<?>> out) {
        PEHKUI.run("writeEntities", () -> PehkuiCompat.writeEntities(entities, out));
    }

    /** Mod state an entity other than a player carries now (snapshots and checkpoints), written after its spawn. */
    public static void writeEntityState(net.minecraft.world.entity.Entity entity, java.util.function.Consumer<Packet<?>> out) {
        if (ARMOURERS_WORKSHOP.on()) {
            ARMOURERS_WORKSHOP.run("writeCurrent", () -> ArmourersWorkshopCompat.writeCurrent(entity, out));
        }
        if (CURIOS.on()) {
            CURIOS.run("writeCurrent", () -> CuriosCompat.writeCurrent(entity, out));
        }
        if (ACCESSORIES.on()) {
            ACCESSORIES.run("writeCurrent", () -> AccessoriesCompat.writeCurrent(entity, out));
        }
        if (IRONS_SPELLS.on()) {
            IRONS_SPELLS.run("writeCurrent", () -> IronsSpellsCompat.writeCurrent(entity, out));
        }
        if (IMMERSIVE_AIRCRAFT.on()) {
            IMMERSIVE_AIRCRAFT.run("writeCurrent", () -> ImmersiveAircraftCompat.writeCurrent(entity, out));
        }
        if (IMMERSIVE_VEHICLES.on()) {
            IMMERSIVE_VEHICLES.run("writeCurrent", () -> ImmersiveVehiclesCompat.writeCurrent(entity, out));
        }
    }

    /** Mod state of the blocks of a chunk just written into a snapshot or checkpoint (Immersive Vehicles blocks). */
    public static void writeChunkState(net.minecraft.world.level.chunk.LevelChunk chunk,
                                       java.util.function.Consumer<Packet<?>> out) {
        if (IMMERSIVE_VEHICLES.on()) {
            IMMERSIVE_VEHICLES.run("writeChunk", () -> ImmersiveVehiclesCompat.writeChunk(chunk, out));
        }
    }

    /**
     * Form and size the recording player's actor needs right after it spawns: the server sent them before the capture
     * saw the actor. Emotes are left out, the local emote poll writes them on a spawn.
     */
    public static void writeActorForm(net.minecraft.world.entity.player.Player player, java.util.UUID target,
                                      java.util.function.Consumer<Packet<?>> out) {
        MORPH.run("writeCurrent", () -> MorphCompat.writeCurrent(player, target, out));
        PEHKUI.run("writeCurrent", () -> PehkuiCompat.writeCurrent(player, out));
        ARMOURERS_WORKSHOP.run("writeCurrent", () -> ArmourersWorkshopCompat.writeCurrent(player, out));
        EPIC_FIGHT.run("writeCurrent", () -> EpicFightCompat.writeCurrent(player, out));
        CURIOS.run("writeCurrent", () -> CuriosCompat.writeCurrent(player, out));
        ACCESSORIES.run("writeCurrent", () -> AccessoriesCompat.writeCurrent(player, out));
        IRONS_SPELLS.run("writeCurrent", () -> IronsSpellsCompat.writeCurrent(player, out));
    }

    /**
     * Mod animation state of the non-player entities of the level (snapshots and checkpoints, after the entities are
     * written): Epic Fight mobs in the middle of an animation.
     */
    public static void writeEntityStates(net.minecraft.client.multiplayer.ClientLevel level,
                                         java.util.function.Consumer<Packet<?>> out) {
        EPIC_FIGHT.run("writeEntities", () -> EpicFightCompat.writeEntities(level, out));
    }

    /**
     * A clientbound packet of a live (recorded) connection, on the network thread; {@code newConnection} on its first.
     * Called for every packet: no allocation.
     */
    public static void onLivePacket(Packet<?> packet, boolean newConnection) {
        if (!DROP_THE_BEAM.on()) {
            return;
        }
        try {
            if (newConnection) {
                DropTheBeamCompat.clearCache();
            }
            DropTheBeamCompat.track(packet);
        } catch (Throwable t) {
            DROP_THE_BEAM.fail("track", t);
        }
    }

    /** Mod state a snapshot or checkpoint must write because the server sends it only on a change. */
    public static void writeSnapshot(java.util.function.Consumer<Packet<?>> out) {
        // First: holders read without these definitions lose every stack, and players and entities follow.
        ACCESSORIES.run("writeSnapshot", () -> AccessoriesCompat.writeSnapshot(out));
        SABLE.run("writeSnapshot", () -> SableCompat.writeSnapshot(out));
        // After the sub-levels: the rope owner blocks must exist, in the world or in a plot.
        SIMULATED.run("writeSnapshot", () -> SimulatedCompat.writeSnapshot(out));
        ARMOURERS_WORKSHOP.run("writeSnapshot", () -> ArmourersWorkshopCompat.writeSnapshot(out));
        IMMERSIVE_PAINTINGS.run("writeSnapshot", () -> ImmersivePaintingsCompat.writeSnapshot(out));
        DROP_THE_BEAM.run("writeSnapshot", () -> DropTheBeamCompat.writeSnapshot(out));
        CUSTOM_NPCS.run("writeSnapshot", () -> CustomNpcsCompat.writeSnapshot(out));
        EXPOSURE.run("writeSnapshot", () -> ExposureCompat.writeSnapshot(out));
    }

    /**
     * The encoded bytes of a recorded packet, with what a mod appends to a vanilla packet restored (Custom NPCs looks).
     * Called for every packet: no allocation.
     */
    public static byte[] patchEncoded(Packet<?> packet, byte[] bytes) {
        if (!CUSTOM_NPCS.on()) {
            return bytes;
        }
        try {
            return CustomNpcsCompat.patchEncoded(packet, bytes);
        } catch (Throwable t) {
            CUSTOM_NPCS.fail("patchEncoded", t);
            return bytes;
        }
    }

    /** A synthetic packet to record on this capture tick (a mod clock sample), or null. */
    public static Packet<?> recordTick(int captureTick) {
        if (!DROP_THE_BEAM.on()) {
            return null;
        }
        try {
            return DropTheBeamCompat.sampleForTick(captureTick);
        } catch (Throwable t) {
            DROP_THE_BEAM.fail("sampleForTick", t);
            return null;
        }
    }

    /** The song starts of the running replay (Drop the Beam), none when that integration is off. */
    public static List<DropTheBeamCompat.SongStart> songStarts() {
        if (!DROP_THE_BEAM.on()) {
            return List.of();
        }
        try {
            return DropTheBeamCompat.songStartTicks();
        } catch (Throwable t) {
            DROP_THE_BEAM.fail("songStartTicks", t);
            return List.of();
        }
    }

    // ---------------------------------------------------------------- editor hooks

    /** Plays (or keeps in sync) an EMOTE action of an actor, see {@link EmotecraftCompat#applyActorEmote}. */
    public static void applyActorEmote(net.minecraft.world.entity.Entity actor, String name, int actionTick, int clipFrom,
                                       double speed, int cursor) {
        if (EMOTECRAFT.on()) {
            EMOTECRAFT.run("applyActorEmote", () -> EmotecraftCompat.applyActorEmote(actor, name, actionTick, clipFrom, speed, cursor));
        }
    }

    /** Stops the emote of an actor, if it plays one. */
    public static void stopActorEmote(net.minecraft.world.entity.Entity actor) {
        if (EMOTECRAFT.on()) {
            EMOTECRAFT.run("stopActorEmote", () -> EmotecraftCompat.stopActorEmote(actor));
        }
    }

    // ---------------------------------------------------------------- export hooks

    /** An export starts (before its pre-roll). */
    public static void onExportStart() {
        DROP_THE_BEAM.run("beginExport", DropTheBeamCompat::beginExport);
    }

    /** An export ended, finished or not. */
    public static void onExportEnd() {
        DROP_THE_BEAM.cleanup("endExport", DropTheBeamCompat::endExport);
    }

    /** A frame was captured: integrations with their own audio engine render its stretch into the export mix. */
    public static void onExportFrame(com.vyrriox.justreplay.export.ExportAudio audio, int frame, int fps, double x, double y, double z,
                                     float yaw, float partialTick) {
        if (DROP_THE_BEAM.on()) {
            DROP_THE_BEAM.run("onExportFrame", () -> DropTheBeamCompat.onExportFrame(audio, frame, fps, x, y, z, yaw, partialTick));
        }
    }

    /**
     * A recorded packet played during a seek, {@code late} ticks before the seek target. Returns the packet to handle
     * in its place, or null to drop it (an emote that has already ended at the target). A failing integration leaves
     * the packet as it was. Called for every packet of a seek: no allocation.
     */
    public static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (late <= 0) {
            return packet;
        }
        if (EMOTECRAFT.on()) {
            try {
                packet = EmotecraftCompat.adjustSkipped(packet, late);
            } catch (Throwable t) {
                EMOTECRAFT.fail("adjustSkipped", t);
            }
        }
        if (packet != null && BETTER_COMBAT.on()) {
            try {
                packet = BetterCombatCompat.adjustSkipped(packet, late);
            } catch (Throwable t) {
                BETTER_COMBAT.fail("adjustSkipped", t);
            }
        }
        if (packet != null && MORPH.on()) {
            try {
                packet = MorphCompat.adjustSkipped(packet, late);
            } catch (Throwable t) {
                MORPH.fail("adjustSkipped", t);
            }
        }
        if (packet != null && PEHKUI.on()) {
            try {
                packet = PehkuiCompat.adjustSkipped(packet, late);
            } catch (Throwable t) {
                PEHKUI.fail("adjustSkipped", t);
            }
        }
        if (packet != null && EPIC_FIGHT.on()) {
            try {
                packet = EpicFightCompat.adjustSkipped(packet, late);
            } catch (Throwable t) {
                EPIC_FIGHT.fail("adjustSkipped", t);
            }
        }
        if (packet != null && IRONS_SPELLS.on()) {
            try {
                packet = IronsSpellsCompat.adjustSkipped(packet, late);
            } catch (Throwable t) {
                IRONS_SPELLS.fail("adjustSkipped", t);
            }
        }
        return packet;
    }

    public static final class Events {
        private Events() {
        }

        /** Before anything renders (mods read their clock while rendering): integration clocks move to this frame. */
        public static void onRenderFrameFirst(float partialTick) {
            if (!DROP_THE_BEAM.on()) {
                return;
            }
            try {
                DropTheBeamCompat.onFrame(partialTick);
            } catch (Throwable t) {
                DROP_THE_BEAM.fail("onFrame", t);
            }
        }

        /** Exports render frames faster or slower than real time: in-world videos are moved to each frame's time. */
        public static void onRenderFrame(float partialTick) {
            if (!WATERFRAMES.on() || !VideoExporter.isActive()) {
                return;
            }
            Playback playback = playback();
            if (playback == null) {
                return;
            }
            try {
                WaterFramesCompat.syncFrame(playback, partialTick);
            } catch (Throwable t) {
                WATERFRAMES.fail("syncFrame", t);
            }
        }
    }
}
