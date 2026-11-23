package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.Checkpoints;
import com.vyrriox.justreplay.format.PacketStream;
import com.vyrriox.justreplay.format.RecordedPacket;
import com.vyrriox.justreplay.record.PacketCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Feeds recorded packets to the client packet listener on the client thread, one recorded tick per client
 * tick. Pause and speed use the vanilla tick rate manager, so every entity, particle and modded renderer
 * follows the replay clock.
 *
 * @author vyrriox
 */
public final class Playback {
    /** Entity id given to the camera so it never collides with a recorded entity. */
    public static final int CAMERA_ENTITY_ID = -42;
    public static final float[] SPEEDS = {0.1F, 0.25F, 0.5F, 1.0F, 2.0F, 4.0F, 8.0F};
    /** A forward seek longer than this restores a checkpoint instead of playing every tick in between. */
    private static final int CHECKPOINT_JUMP_TICKS = 1200;

    private final PacketStream packets;
    private final int duration;
    private final Set<Class<?>> failedTypes = Collections.synchronizedSet(new HashSet<>());
    /** Ids of recorded mod payloads skipped because no installed mod receives them, logged once each. */
    private final Set<net.minecraft.resources.ResourceLocation> skippedPayloads = new HashSet<>();
    /** Recorded packets that could not be read or played: skipped and counted, summed up when the replay ends. */
    private final com.vyrriox.justreplay.record.SkippedPackets skipped =
            new com.vyrriox.justreplay.record.SkippedPackets("Replay", JustReplay.LOGGER::warn);
    private PacketCodec codec;
    private int cursor;
    private boolean paused;
    private int speedIndex = 3;
    private boolean worldReset;
    private int handledInLastFlush;
    private static boolean dispatching;
    private static int dispatchTick = -1;
    private float speedAccumulator;
    private int skippingTo = -1;
    private int lastStep = 1;
    private int[] checkpoints = new int[0];
    private final ChunkRetention chunks = new ChunkRetention();
    private int seekCount;
    /** Checkpoint the last seek restored, or -1 when it played the stream without one. */
    private int lastSeekCheckpoint = -1;
    /** Tick {@link #playTo} pauses on, or -1. */
    private int pauseAt = -1;
    /** Speed track of the project, followed by previews (exports run their own clock), or null. */
    private java.util.function.Supplier<com.vyrriox.justreplay.editor.SpeedTrack> speedTrack;
    /** Client ticks left on a freeze of the speed track. */
    private int holdTicks;
    /** Tick of the last freeze held, so a freeze is held once each time the cursor reaches it. */
    private int heldTick = -1;

    public Playback(PacketStream packets, int durationTicks) {
        this.packets = packets;
        int last = packets.lastTick();
        this.duration = Math.max(durationTicks, last);
    }

    public int cursor() {
        return cursor;
    }

    public int duration() {
        return duration;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        pauseAt = -1;
    }

    /**
     * Plays from the cursor and pauses on {@code target}: entities, particles and the world tick on the way, which a
     * seek alone does not do.
     */
    public void playTo(int target) {
        paused = target <= cursor;
        pauseAt = paused ? -1 : Math.min(duration, target);
    }

    /** Number of seeks so far; anything that follows the replay clock resynchronizes when it changes. */
    public int seekCount() {
        return seekCount;
    }

    /** Tick of the checkpoint the last seek restored, or -1 when it played the stream without one. */
    public int lastSeekCheckpoint() {
        return lastSeekCheckpoint;
    }

    /** Recorded ticks played during the last client tick (0 when paused). */
    public int lastStep() {
        return lastStep;
    }

    public float speed() {
        return SPEEDS[speedIndex];
    }

    public void changeSpeed(int delta) {
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, speedIndex + delta));
    }

    public void resetSpeed() {
        speedIndex = 3;
    }

    /** Position of the current speed in {@link #SPEEDS}. */
    public int speedIndex() {
        return speedIndex;
    }

    public void setSpeedIndex(int index) {
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, index));
    }

    /** The speed track previews follow; exports drive the clock themselves. */
    public void setSpeedTrack(java.util.function.Supplier<com.vyrriox.justreplay.editor.SpeedTrack> track) {
        this.speedTrack = track;
    }

    private com.vyrriox.justreplay.editor.SpeedTrack activeTrack() {
        if (speedTrack == null || com.vyrriox.justreplay.export.VideoExporter.isActive()) {
            return null;
        }
        com.vyrriox.justreplay.editor.SpeedTrack track = speedTrack.get();
        return track == null || track.isEmpty() ? null : track;
    }

    /** Speed factor of the speed track at the cursor (1 without one), 0 while a freeze holds the picture. */
    public float trackSpeed() {
        if (holdTicks > 0) {
            return 0.0F;
        }
        com.vyrriox.justreplay.editor.SpeedTrack track = activeTrack();
        return track == null ? 1.0F : (float) track.speedAt(cursor);
    }

    /** The speed the replay runs at now: the chosen speed times the speed track's. */
    public float effectiveSpeed() {
        return speed() * trackSpeed();
    }

    /** True while a freeze of the speed track holds the picture. */
    public boolean isHolding() {
        return holdTicks > 0;
    }

    /** False when the speed track silences the game sounds and voices now (too slow, too fast or frozen). */
    public boolean trackSoundsAudible() {
        com.vyrriox.justreplay.editor.SpeedTrack track = activeTrack();
        return track == null || track.soundsAt(trackSpeed(), holdTicks > 0);
    }

    /** Builds the codec from the replay connection and plays the first tick. */
    public void start(ClientPacketListener listener) {
        codec = PacketCodec.of(listener);
        checkpoints = Checkpoints.list(packets.dir());
        packets.rewind();
        cursor = 0;
        flushTo(0);
    }

    public void tick(Minecraft mc) {
        if (codec == null) {
            return;
        }
        lastStep = 0;
        if (!paused && !mc.isPaused()) {
            if (holdTicks > 0) {
                // A freeze of the speed track: the picture stays, real time goes on.
                holdTicks--;
            } else if (cursor < duration) {
                // The client never ticks faster than 20 TPS: above x1, several recorded ticks run per client tick.
                speedAccumulator += Math.max(1.0F, effectiveSpeed());
                int steps = (int) speedAccumulator;
                speedAccumulator -= steps;
                com.vyrriox.justreplay.editor.SpeedTrack track = activeTrack();
                for (int i = 0; i < steps && cursor < duration; i++) {
                    cursor++;
                    flushTo(cursor);
                    lastStep++;
                    if (pauseAt >= 0 && cursor >= pauseAt) {
                        paused = true;
                        pauseAt = -1;
                        break;
                    }
                    double freeze = track == null ? 0.0 : track.freezeAt(cursor);
                    if (freeze > 0.0 && heldTick != cursor) {
                        heldTick = cursor;
                        holdTicks = Math.max(1, (int) Math.round(freeze * 20.0 / Math.max(0.1F, speed())));
                        speedAccumulator = 0.0F;
                        break;
                    }
                }
            } else {
                paused = true;
            }
        }
        try {
            chunks.tick(mc);
        } catch (Throwable t) {
            logOnce(ChunkRetention.class, "Cannot evict far chunks: " + t);
        }
        applyTickRate(mc);
    }

    /**
     * Jumps to a tick. Going back (or far forward) restores the latest checkpoint before the target and plays the
     * stream from there; without one, going back replays the stream from its start. Both rebuild the world.
     */
    public void seek(int target) {
        target = Math.max(0, Math.min(duration, target));
        long seekStarted = System.nanoTime();
        int seekFrom = cursor;
        int usedCheckpoint = -1;
        seekCount++;
        pauseAt = -1;
        holdTicks = 0;
        heldTick = -1;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Vec3 camPos = player != null ? player.position() : null;
        float yaw = player != null ? player.getYRot() : 0;
        float pitch = player != null ? player.getXRot() : 0;
        boolean backward = target < cursor;
        if (backward) {
            mc.gui.getChat().clearMessages(false);
        }
        skippingTo = target;
        try {
            boolean restored = false;
            if (backward || target - cursor > CHECKPOINT_JUMP_TICKS) {
                int checkpoint = latestCheckpoint(target);
                if (checkpoint >= 0 && (backward || checkpoint > cursor)) {
                    restored = restoreCheckpoint(mc, checkpoint);
                    JustReplay.LOGGER.debug("Seek to {} from checkpoint {}: {}", target, checkpoint, restored);
                    usedCheckpoint = restored ? checkpoint : -1;
                }
            }
            if (!restored && backward) {
                com.vyrriox.justreplay.compat.ReplayCompat.onWorldRebuild();
                ReplayPacks.onWorldRebuild();
                packets.rewind();
                cursor = 0;
            }
            flushTo(target);
        } finally {
            skippingTo = -1;
        }
        cursor = target;
        lastSeekCheckpoint = usedCheckpoint;
        if (camPos != null && mc.player != null) {
            placeCamera(mc.player, camPos, yaw, pitch);
        }
        applyTickRate(mc);
        if (JustReplay.LOGGER.isDebugEnabled()) {
            JustReplay.LOGGER.debug("Seek {} -> {} took {} ms, {} stream packets{}", seekFrom, target,
                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - seekStarted) / 1e6), handledInLastFlush,
                    usedCheckpoint >= 0 ? " after the checkpoint at " + usedCheckpoint : backward ? " from the start" : "");
        }
    }

    /** Latest checkpoint at or before a tick, or -1. */
    private int latestCheckpoint(int tick) {
        int best = -1;
        for (int checkpoint : checkpoints) {
            if (checkpoint > tick) {
                break;
            }
            best = checkpoint;
        }
        return best;
    }

    /** Handles the packets of a checkpoint and moves the stream right after it. False when it cannot be read. */
    private boolean restoreCheckpoint(Minecraft mc, int tick) {
        List<RecordedPacket> snapshot;
        try {
            snapshot = Checkpoints.read(packets.dir(), tick);
        } catch (Exception e) {
            logOnce(Checkpoints.class, "Cannot read the checkpoint at tick " + tick + ", replaying from the start: " + e);
            return false;
        }
        if (snapshot.isEmpty()) {
            return false;
        }
        com.vyrriox.justreplay.compat.ReplayCompat.onWorldRebuild();
        ReplayPacks.onWorldRebuild();
        for (RecordedPacket recorded : snapshot) {
            handle(mc, recorded);
        }
        packets.seekToTick(tick);
        cursor = tick;
        return true;
    }

    /** What a new connection starts without and a login packet does not clear: scoreboard, boss bars, tab list text. */
    private static void resetConnectionState(Minecraft mc, ClientPacketListener listener) {
        try {
            Scoreboard scoreboard = listener.scoreboard();
            for (Objective objective : List.copyOf(scoreboard.getObjectives())) {
                scoreboard.removeObjective(objective);
            }
            for (PlayerTeam team : List.copyOf(scoreboard.getPlayerTeams())) {
                scoreboard.removePlayerTeam(team);
            }
            mc.gui.getBossOverlay().reset();
            mc.gui.getTabList().reset();
            // Tab list entries of players from later in the recording, kept by a login. The camera keeps its own.
            List<java.util.UUID> known = new java.util.ArrayList<>();
            for (net.minecraft.client.multiplayer.PlayerInfo info : listener.getOnlinePlayers()) {
                java.util.UUID id = info.getProfile().getId();
                if (mc.player == null || !id.equals(mc.player.getUUID())) {
                    known.add(id);
                }
            }
            if (!known.isEmpty()) {
                listener.handlePlayerInfoRemove(new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(known));
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot reset the scoreboard and overlays: {}", t.toString());
        }
    }

    private void flushTo(int tick) {
        Minecraft mc = Minecraft.getInstance();
        handledInLastFlush = 0;
        RecordedPacket next;
        while ((next = packets.peek()) != null && next.tick() <= tick) {
            packets.advance();
            handle(mc, next);
            handledInLastFlush++;
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Plays one recorded packet. Whatever goes wrong with it (a mod payload that cannot be read or handled, a filter
     * that fails) skips that packet only: it is counted and the replay goes on with the next one.
     */
    private void handle(Minecraft mc, RecordedPacket recorded) {
        try {
            handleChecked(mc, recorded);
        } catch (Throwable t) {
            PacketCodec c = codec;
            skipped.fail(com.vyrriox.justreplay.record.SkippedPackets.Stage.PLAY, c != null ? c.describe(recorded.data()) : null, t);
        }
    }

    /** The recorded packets this replay skipped since it started or since the last {@link #logSkipped}. */
    public com.vyrriox.justreplay.record.SkippedPackets skipped() {
        return skipped;
    }

    /** Logs how many recorded packets this replay skipped, if any, then counts from zero. */
    public void logSkipped() {
        skipped.flush(JustReplay.LOGGER::warn);
    }

    private void handleChecked(Minecraft mc, RecordedPacket recorded) {
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        net.minecraft.resources.ResourceLocation unregistered = codec.unregisteredPayload(recorded.data());
        if (unregistered != null) {
            // A mod of the recording missing here (ParCool, Better Combat...): its packets are skipped quietly.
            if (skippedPayloads.add(unregistered)) {
                JustReplay.LOGGER.info("Skipping recorded {} packets: no mod here receives them", unregistered);
            }
            skipped.count(com.vyrriox.justreplay.record.SkippedPackets.Stage.NO_RECEIVER, unregistered.toString());
            return;
        }
        // A packet that cannot be read (its mod changed or is missing) is counted and skipped.
        Packet<? super ClientGamePacketListener> packet = skipped.read(recorded.data(), codec::decode, codec::describe);
        if (packet == null) {
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chat) {
            // Signed chat cannot be validated in a replay (expired keys, missing signature cache); show it unsigned.
            packet = unsign(chat);
            if (packet == null) {
                return;
            }
        }
        if (com.vyrriox.justreplay.compat.ReplayCompat.consumeRecorded(packet, recorded.tick())) {
            return;
        }
        if (recorded.tick() < skippingTo && PacketFilter.isTransient(packet)) {
            // Sounds and effects of skipped time would all fire at once.
            return;
        }
        if (ReplayPacks.consume(packet)) {
            return;
        }
        if (PacketFilter.shouldDrop(packet)) {
            return;
        }
        packet = (Packet<? super ClientGamePacketListener>) com.vyrriox.justreplay.compat.ReplayCompat.filterPlayback(packet);
        if (packet == null) {
            return;
        }
        if (recorded.tick() < skippingTo) {
            // Mod state with its own clock (emotes) starts late by the time skipped, or not at all once over.
            packet = (Packet<? super ClientGamePacketListener>) com.vyrriox.justreplay.compat.ReplayCompat.adjustSkipped(packet,
                    skippingTo - recorded.tick());
            if (packet == null) {
                return;
            }
        }
        packet = chunks.filter(packet);
        if (packet == null) {
            return;
        }
        if (packet instanceof ClientboundLoginPacket login) {
            resetConnectionState(mc, listener);
            packet = rewriteLogin(login, chunks.onLogin(login));
        }
        // Playing through a respawn builds a new camera player at the origin; a seek puts the camera back itself.
        boolean liveRespawn = packet instanceof ClientboundRespawnPacket && skippingTo < 0 && mc.player != null && mc.level != null;
        Vec3 camPos = liveRespawn ? mc.player.position() : null;
        float camYaw = liveRespawn ? mc.player.getYRot() : 0;
        float camPitch = liveRespawn ? mc.player.getXRot() : 0;
        Object camDimension = liveRespawn ? mc.level.dimension() : null;
        dispatching = true;
        dispatchTick = recorded.tick();
        try {
            ((Packet<ClientGamePacketListener>) packet).handle(listener);
        } catch (Throwable t) {
            skipped.fail(com.vyrriox.justreplay.record.SkippedPackets.Stage.PLAY, PacketCodec.typeName(packet), t);
        } finally {
            dispatching = false;
            dispatchTick = -1;
        }
        chunks.afterHandle(mc);
        if (packet instanceof ClientboundLoginPacket && mc.player != null && mc.level != null && mc.player.level() != mc.level) {
            // A login keeps the existing camera player and adds it to the new level without telling it: mods that
            // reach the level through the player (Sable creating airships) would act on the level before the seek.
            ((com.vyrriox.justreplay.mixin.EntityInvoker) mc.player).justreplay$setLevel(mc.level);
        }
        if (liveRespawn && mc.player != null && mc.level != null) {
            if (mc.level.dimension().equals(camDimension)) {
                placeCamera(mc.player, camPos, camYaw, camPitch);
            } else {
                worldReset = true;
            }
        }
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            enforceCamera(mc);
        }
    }

    /** True while a recorded packet is being handled; screen openings and disconnects it causes are ignored. */
    public static boolean isDispatching() {
        return dispatching;
    }

    /**
     * Recorded tick of the packet being handled, or -1. During a seek many ticks are handled at once: state with its own
     * clock (an in-world video) uses this tick, not the cursor, as the time the packet describes.
     */
    public static int dispatchTick() {
        return dispatchTick;
    }

    private static Packet<? super ClientGamePacketListener> unsign(net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chat) {
        net.minecraft.network.chat.FilterMask mask = chat.filterMask();
        net.minecraft.network.chat.Component content;
        if (mask.isEmpty()) {
            content = chat.unsignedContent() != null ? chat.unsignedContent()
                    : net.minecraft.network.chat.Component.literal(chat.body().content());
        } else {
            content = mask.applyWithFormatting(chat.body().content());
        }
        return content == null ? null : new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(content, chat.chatType());
    }

    private static ClientboundLoginPacket rewriteLogin(ClientboundLoginPacket login, int chunkRadius) {
        CommonPlayerSpawnInfo s = login.commonPlayerSpawnInfo();
        CommonPlayerSpawnInfo spawn = new CommonPlayerSpawnInfo(s.dimensionType(), s.dimension(), s.seed(), GameType.SPECTATOR,
                s.previousGameType(), s.isDebug(), s.isFlat(), s.lastDeathLocation(), s.portalCooldown());
        return new ClientboundLoginPacket(CAMERA_ENTITY_ID, login.hardcore(), login.levels(), login.maxPlayers(), chunkRadius,
                login.simulationDistance(), false, false, login.doLimitedCrafting(), spawn, false);
    }

    /** Keeps the local player a free flying spectator, whatever the recorded packets did. */
    public static void enforceCamera(Minecraft mc) {
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            mc.gameMode.setLocalMode(GameType.SPECTATOR);
        }
        if (mc.player != null && mc.getConnection() != null) {
            // The recorded tab list may carry the viewer's own UUID with another game mode.
            net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null && info.getGameMode() != GameType.SPECTATOR) {
                ((com.vyrriox.justreplay.mixin.PlayerInfoAccessor) info).justreplay$setGameMode(GameType.SPECTATOR);
            }
        }
        if (mc.player != null) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().invulnerable = true;
        }
    }

    public boolean consumeWorldReset() {
        boolean reset = worldReset;
        worldReset = false;
        return reset;
    }

    public static void placeCamera(LocalPlayer player, Vec3 pos, float yaw, float pitch) {
        player.setPos(pos.x, pos.y, pos.z);
        player.xo = player.xOld = pos.x;
        player.yo = player.yOld = pos.y;
        player.zo = player.zOld = pos.z;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;
        player.setDeltaMovement(Vec3.ZERO);
    }

    private void applyTickRate(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) {
            return;
        }
        TickRateManager manager = mc.level.tickRateManager();
        // A freeze holds the world like a pause while the client keeps ticking at full rate to count it.
        boolean frozen = paused || holdTicks > 0;
        float rate = holdTicks > 0 ? 20.0F : 20.0F * Math.min(1.0F, Math.max(0.05F, effectiveSpeed()));
        if (manager.tickrate() != rate || manager.isFrozen() != frozen) {
            try {
                new ClientboundTickingStatePacket(rate, frozen).handle(mc.getConnection());
            } catch (Throwable t) {
                logOnce(ClientboundTickingStatePacket.class, "Cannot apply tick rate: " + t);
            }
        }
    }

    private void logOnce(Class<?> type, String message) {
        if (failedTypes.add(type)) {
            JustReplay.LOGGER.warn(message);
        }
    }
}
