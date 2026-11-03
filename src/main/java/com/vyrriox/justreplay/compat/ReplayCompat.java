package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Entry point of the optional mod integrations: the replay session, the playback and the recorder call these hooks,
 * which forward them to each integration whose mod is installed. Every integration reaches its mod by reflection
 * (or through optional mixins), so nothing here loads a class of a missing mod.
 *
 * @author vyrriox
 */
public final class ReplayCompat {
    static final boolean WATERFRAMES = isLoaded("waterframes");
    static final boolean EMOTECRAFT = isLoaded("emotecraft");
    static final boolean DISTANT_HORIZONS = isLoaded("distanthorizons");
    static final boolean DROP_THE_BEAM = isLoaded("dropthebeam");
    static final boolean PARCOOL = isLoaded("parcool");
    static final boolean BETTER_COMBAT = isLoaded("bettercombat");
    static final boolean MORPH = isLoaded("morph");
    static final boolean SABLE = isLoaded("sable");
    static final boolean AERONAUTICS = isLoaded("aeronautics");
    static final boolean SIMULATED = isLoaded("simulated");
    static final boolean PEHKUI = isLoaded("pehkui");
    static final boolean CUSTOM_NPCS = isLoaded("customnpcs");
    static final boolean ARMOURERS_WORKSHOP = isLoaded("armourers_workshop");
    static final boolean EPIC_FIGHT = isLoaded("epicfight");
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    private ReplayCompat() {
    }

    private static boolean isLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Logs an integration failure once per key, then the integration keeps going without that feature. */
    static void warnOnce(String key, String message, Throwable t) {
        if (FAILED.add(key)) {
            JustReplay.LOGGER.warn("{}: {}", message, t == null ? "" : t.toString());
        }
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

    // ---------------------------------------------------------------- session hooks

    /**
     * Called off the render thread when a replay is about to open, before its world is created: integrations copy or
     * prepare what the replay world needs (Distant Horizons' recorded LODs).
     */
    public static void prepareReplay(com.vyrriox.justreplay.format.ReplayMeta meta) {
        if (DISTANT_HORIZONS) {
            try {
                DistantHorizonsCompat.prepare(meta);
            } catch (Throwable t) {
                warnOnce("dh.prepare", "Cannot prepare the Distant Horizons LODs for the replay", t);
            }
        }
    }

    /** Called once the replay world has fully closed (its integrated server stopped), or after it failed to open. */
    public static void onReplayWorldClosed() {
        if (DISTANT_HORIZONS) {
            try {
                DistantHorizonsCompat.onReplayWorldClosed();
            } catch (Throwable t) {
                warnOnce("dh.closed", "Cannot clean up the Distant Horizons replay data", t);
            }
        }
    }

    /** Called once when replay playback starts. */
    public static void onReplayStart(Minecraft mc) {
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.onReplayStart();
        }
        if (BETTER_COMBAT) {
            BetterCombatCompat.onReplayStart();
        }
        if (DROP_THE_BEAM) {
            ReplaySession session = ReplaySession.current();
            DropTheBeamCompat.onReplayStart(session != null ? session.dir() : null);
        }
    }

    /** Called every client tick, replay or not. */
    public static void clientTick(Minecraft mc) {
        if (DISTANT_HORIZONS && !ReplaySession.isRunning()) {
            DistantHorizonsCompat.recoverAfterInterruptedReplay();
        }
        if (SABLE && !sableChecked) {
            // Resolved once up front, so the detection line (or the failure) shows in the log at startup.
            sableChecked = true;
            SableCompat.reflect();
        }
    }

    private static boolean sableChecked;

    /** Called every client tick of a running replay, after the playback tick. */
    public static void tick(Minecraft mc, ReplaySession session) {
        if (WATERFRAMES) {
            try {
                WaterFramesCompat.tick(mc, session.playback());
            } catch (Throwable t) {
                warnOnce("waterframes.tick", "WaterFrames sync failed", t);
            }
        }
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.tick();
        }
        if (DROP_THE_BEAM) {
            try {
                DropTheBeamCompat.tick(mc, session);
            } catch (Throwable t) {
                warnOnce("dropthebeam.tick", "Drop the Beam sync failed", t);
            }
        }
        if (EPIC_FIGHT) {
            EpicFightCompat.applyPending();
        }
    }

    /** Called when the replay session closes. */
    public static void onReplayEnd() {
        if (WATERFRAMES) {
            WaterFramesCompat.clear();
        }
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.onReplayEnd();
        }
        if (DROP_THE_BEAM) {
            DropTheBeamCompat.onReplayEnd();
        }
        if (ARMOURERS_WORKSHOP) {
            ArmourersWorkshopCompat.onReplayEnd();
        }
        if (EPIC_FIGHT) {
            EpicFightCompat.onReplayEnd();
        }
    }

    /**
     * A recorded packet about to be handled at its recorded tick (seeks included): true when an integration consumed
     * it and it must not reach the client (Drop the Beam's clock packets, which belong to the recording process).
     */
    public static boolean consumeRecorded(Packet<?> packet, int tick) {
        if (EPIC_FIGHT) {
            // The previous packet has been handled: its Epic Fight animation can be moved to its time.
            EpicFightCompat.applyPending();
        }
        if (BETTER_COMBAT) {
            BetterCombatCompat.onRecorded(packet);
        }
        if (MORPH && MorphCompat.consumeRecorded(packet)) {
            return true;
        }
        return DROP_THE_BEAM && DropTheBeamCompat.consumeRecorded(packet, tick);
    }

    /**
     * A recorded packet about to be handled, after the generic filters: returns the packet to handle in its place, or
     * null to drop it (Sable payloads meant for the live player only, pose timing scaled to the playback speed).
     */
    public static Packet<?> filterPlayback(Packet<?> packet) {
        if (SABLE) {
            packet = SableCompat.filterPlayback(packet, playback());
        }
        if (CUSTOM_NPCS && packet != null) {
            packet = CustomNpcsCompat.filterPlayback(packet);
        }
        if (EPIC_FIGHT && packet != null) {
            packet = EpicFightCompat.filterPlayback(packet);
        }
        return packet;
    }

    /** True for a chunk of a mod's far away storage area that must stay loaded whatever the camera distance (Sable plots). */
    public static boolean isDetachedChunk(int x, int z) {
        return SABLE && SableCompat.isPlotChunk(x, z);
    }

    /** A packet the client is about to send to the replay host: true to drop it (requests of mods with no server there). */
    public static boolean blockServerbound(Packet<?> packet) {
        return DROP_THE_BEAM && DropTheBeamCompat.blockServerbound(packet)
                || PARCOOL && ParCoolCompat.blockServerbound(packet)
                || BETTER_COMBAT && BetterCombatCompat.blockServerbound(packet)
                || MORPH && MorphCompat.blockServerbound(packet)
                || CUSTOM_NPCS && CustomNpcsCompat.blockServerbound(packet)
                || EPIC_FIGHT && EpicFightCompat.blockServerbound(packet);
    }

    // ---------------------------------------------------------------- recording hooks

    /**
     * Notes in a recording's meta what integrations need to know about the level being recorded (the Distant Horizons
     * LOD folder of its dimension). Called every client tick of a recording, and with {@code force} when a meta is made.
     */
    public static void describeLevel(com.vyrriox.justreplay.format.ReplayMeta meta, net.minecraft.world.level.Level level,
                                     boolean force) {
        if (DISTANT_HORIZONS) {
            DistantHorizonsCompat.describe(meta, level, force);
        }
    }

    /**
     * A packet the client sends on a live connection (any thread): integrations record what the server never sends
     * back to its sender (Better Combat's attack animations).
     */
    public static void onLiveServerbound(Packet<?> packet) {
        if (BETTER_COMBAT) {
            try {
                BetterCombatCompat.onLiveServerbound(packet);
            } catch (Throwable t) {
                warnOnce("bettercombat.serverbound", "Cannot record a Better Combat attack", t);
            }
        }
        if (EPIC_FIGHT) {
            try {
                EpicFightCompat.onLiveServerbound(packet);
            } catch (Throwable t) {
                warnOnce("epicfight.serverbound", "Cannot record an Epic Fight request of the local player", t);
            }
        }
    }

    /**
     * A captured clientbound packet about to be written into the recording (network thread): returns the packet to
     * write in its place. ParCool echoes the recording player's own actions back to it under its real UUID; they are
     * written for its actor, as another client receives them. Morph forms naming the recording player's UUID name the
     * actor's instead. The packet the client handles is never changed.
     */
    public static Packet<?> rewriteRecorded(Packet<?> packet, java.util.UUID localUuid, java.util.UUID actorUuid) {
        if (PARCOOL) {
            try {
                packet = ParCoolCompat.rewriteRecorded(packet, localUuid, actorUuid);
            } catch (Throwable t) {
                warnOnce("parcool.rewrite", "Cannot record the local player's ParCool actions", t);
            }
        }
        if (MORPH) {
            try {
                packet = MorphCompat.rewriteRecorded(packet, localUuid, actorUuid);
            } catch (Throwable t) {
                warnOnce("morph.rewrite", "Cannot record the local player's Morph forms for its actor", t);
            }
        }
        return packet;
    }

    /**
     * Mod animation state a player is in now (snapshots and checkpoints), written as another client receives it, for
     * the player with this UUID in the replay (the actor for the recording player).
     */
    public static void writePlayerState(net.minecraft.world.entity.player.Player player, java.util.UUID target,
                                        java.util.function.Consumer<Packet<?>> out) {
        EmotecraftCompat.writeCurrent(player, target, out);
        if (PARCOOL) {
            ParCoolCompat.writeCurrent(player, target, out);
        }
        if (MORPH) {
            MorphCompat.writeCurrent(player, target, out);
        }
        if (PEHKUI) {
            PehkuiCompat.writeCurrent(player, out);
        }
        if (ARMOURERS_WORKSHOP) {
            ArmourersWorkshopCompat.writeCurrent(player, out);
        }
        if (EPIC_FIGHT) {
            // Keyed by entity id, which the actor shares with the recording player.
            EpicFightCompat.writeCurrent(player, out);
        }
    }

    /** Mod state of the non-player entities of a snapshot or checkpoint, written after they spawn (Pehkui scales). */
    public static void writeEntityState(Iterable<net.minecraft.world.entity.Entity> entities,
                                        java.util.function.Consumer<Packet<?>> out) {
        if (PEHKUI) {
            PehkuiCompat.writeEntities(entities, out);
        }
    }

    /** Mod state an entity other than a player carries now (snapshots and checkpoints), written after its spawn. */
    public static void writeEntityState(net.minecraft.world.entity.Entity entity, java.util.function.Consumer<Packet<?>> out) {
        if (ARMOURERS_WORKSHOP) {
            ArmourersWorkshopCompat.writeCurrent(entity, out);
        }
    }

    /**
     * Form and size the recording player's actor needs right after it spawns: the server sent them before the capture
     * saw the actor. Emotes are left out, the local emote poll writes them on a spawn.
     */
    public static void writeActorForm(net.minecraft.world.entity.player.Player player, java.util.UUID target,
                                      java.util.function.Consumer<Packet<?>> out) {
        if (MORPH) {
            MorphCompat.writeCurrent(player, target, out);
        }
        if (PEHKUI) {
            PehkuiCompat.writeCurrent(player, out);
        }
        if (ARMOURERS_WORKSHOP) {
            ArmourersWorkshopCompat.writeCurrent(player, out);
        }
        if (EPIC_FIGHT) {
            EpicFightCompat.writeCurrent(player, out);
        }
    }

    /**
     * Mod animation state of the non-player entities of the level (snapshots and checkpoints, after the entities are
     * written): Epic Fight mobs in the middle of an animation.
     */
    public static void writeEntityStates(net.minecraft.client.multiplayer.ClientLevel level,
                                         java.util.function.Consumer<Packet<?>> out) {
        if (EPIC_FIGHT) {
            EpicFightCompat.writeEntities(level, out);
        }
    }

    /** A clientbound packet of a live (recorded) connection, on the network thread; {@code newConnection} on its first. */
    public static void onLivePacket(Packet<?> packet, boolean newConnection) {
        if (!DROP_THE_BEAM) {
            return;
        }
        if (newConnection) {
            DropTheBeamCompat.clearCache();
        }
        DropTheBeamCompat.track(packet);
    }

    /** Mod state a snapshot or checkpoint must write because the server sends it only on a change. */
    public static void writeSnapshot(java.util.function.Consumer<Packet<?>> out) {
        if (SABLE) {
            try {
                SableCompat.writeSnapshot(out);
            } catch (Throwable t) {
                warnOnce("sable.snapshot", "Cannot write the Sable sub-levels into the snapshot", t);
            }
        }
        if (SIMULATED) {
            try {
                // After the sub-levels: the rope owner blocks must exist, in the world or in a plot.
                SimulatedCompat.writeSnapshot(out);
            } catch (Throwable t) {
                warnOnce("simulated.snapshot", "Cannot write the Simulated ropes into the snapshot", t);
            }
        }
        if (ARMOURERS_WORKSHOP) {
            try {
                ArmourersWorkshopCompat.writeSnapshot(out);
            } catch (Throwable t) {
                warnOnce("aw.snapshot", "Cannot write the Armourer's Workshop skins into the snapshot", t);
            }
        }
        if (DROP_THE_BEAM) {
            try {
                DropTheBeamCompat.writeSnapshot(out);
            } catch (Throwable t) {
                warnOnce("dropthebeam.snapshot", "Cannot write the Drop the Beam state into the snapshot", t);
            }
        }
        if (CUSTOM_NPCS) {
            try {
                CustomNpcsCompat.writeSnapshot(out);
            } catch (Throwable t) {
                warnOnce("customnpcs.snapshot", "Cannot write the Custom NPCs player skins into the snapshot", t);
            }
        }
    }

    /** The encoded bytes of a recorded packet, with what a mod appends to a vanilla packet restored (Custom NPCs looks). */
    public static byte[] patchEncoded(Packet<?> packet, byte[] bytes) {
        return CUSTOM_NPCS ? CustomNpcsCompat.patchEncoded(packet, bytes) : bytes;
    }

    /** A synthetic packet to record on this capture tick (a mod clock sample), or null. */
    public static Packet<?> recordTick(int captureTick) {
        return DROP_THE_BEAM ? DropTheBeamCompat.sampleForTick(captureTick) : null;
    }

    // ---------------------------------------------------------------- export hooks

    /** An export starts (before its pre-roll). */
    public static void onExportStart() {
        if (DROP_THE_BEAM) {
            DropTheBeamCompat.beginExport();
        }
    }

    /** An export ended, finished or not. */
    public static void onExportEnd() {
        if (DROP_THE_BEAM) {
            DropTheBeamCompat.endExport();
        }
    }

    /** A frame was captured: integrations with their own audio engine render its stretch into the export mix. */
    public static void onExportFrame(com.vyrriox.justreplay.export.ExportAudio audio, int frame, int fps, double x, double y, double z,
                                     float yaw, float partialTick) {
        if (DROP_THE_BEAM) {
            try {
                DropTheBeamCompat.onExportFrame(audio, frame, fps, x, y, z, yaw, partialTick);
            } catch (Throwable t) {
                warnOnce("dropthebeam.export", "Cannot render Drop the Beam's audio for the export", t);
            }
        }
    }

    /**
     * A recorded packet played during a seek, {@code late} ticks before the seek target. Returns the packet to handle
     * in its place, or null to drop it (an emote that has already ended at the target).
     */
    public static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (EMOTECRAFT && late > 0) {
            packet = EmotecraftCompat.adjustSkipped(packet, late);
        }
        if (BETTER_COMBAT && late > 0 && packet != null) {
            packet = BetterCombatCompat.adjustSkipped(packet, late);
        }
        if (MORPH && late > 0 && packet != null) {
            packet = MorphCompat.adjustSkipped(packet, late);
        }
        if (PEHKUI && late > 0 && packet != null) {
            packet = PehkuiCompat.adjustSkipped(packet, late);
        }
        if (EPIC_FIGHT && late > 0 && packet != null) {
            packet = EpicFightCompat.adjustSkipped(packet, late);
        }
        return packet;
    }

    @EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        /** Before anything renders (mods read their clock while rendering): integration clocks move to this frame. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onRenderFrameFirst(RenderFrameEvent.Pre event) {
            if (!DROP_THE_BEAM) {
                return;
            }
            try {
                DropTheBeamCompat.onFrame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } catch (Throwable t) {
                warnOnce("dropthebeam.frame", "Drop the Beam clock sync failed", t);
            }
        }

        /** Exports render frames faster or slower than real time: in-world videos are moved to each frame's time. */
        @SubscribeEvent
        public static void onRenderFrame(RenderFrameEvent.Pre event) {
            if (!WATERFRAMES || !VideoExporter.isActive()) {
                return;
            }
            Playback playback = playback();
            if (playback == null) {
                return;
            }
            try {
                WaterFramesCompat.syncFrame(playback, event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } catch (Throwable t) {
                warnOnce("waterframes.frame", "WaterFrames export sync failed", t);
            }
        }
    }
}
