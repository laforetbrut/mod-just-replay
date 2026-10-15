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
    }

    /** Number of seeks so far; anything that follows the replay clock resynchronizes when it changes. */
    public int seekCount() {
        return seekCount;
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
            if (cursor < duration) {
                // The client never ticks faster than 20 TPS: above x1, several recorded ticks run per client tick.
                speedAccumulator += Math.max(1.0F, speed());
                int steps = (int) speedAccumulator;
                speedAccumulator -= steps;
                for (int i = 0; i < steps && cursor < duration; i++) {
                    cursor++;
                    flushTo(cursor);
                    lastStep++;
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
        seekCount++;
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
                }
            }
            if (!restored && backward) {
                packets.rewind();
                cursor = 0;
            }
            flushTo(target);
        } finally {
            skippingTo = -1;
        }
        cursor = target;
        if (camPos != null && mc.player != null) {
            placeCamera(mc.player, camPos, yaw, pitch);
        }
        applyTickRate(mc);
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
    private void handle(Minecraft mc, RecordedPacket recorded) {
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        Packet<? super ClientGamePacketListener> packet;
        try {
            packet = codec.decode(recorded.data());
        } catch (Throwable t) {
            logOnce(RecordedPacket.class, "Cannot decode a recorded packet (mod missing or changed?): " + t);
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chat) {
            // Signed chat cannot be validated in a replay (expired keys, missing signature cache); show it unsigned.
            packet = unsign(chat);
            if (packet == null) {
                return;
            }
        }
        if (recorded.tick() < skippingTo && PacketFilter.isTransient(packet)) {
            // Sounds and effects of skipped time would all fire at once.
            return;
        }
        if (PacketFilter.shouldDrop(packet)) {
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
        dispatching = true;
        dispatchTick = recorded.tick();
        try {
            ((Packet<ClientGamePacketListener>) packet).handle(listener);
        } catch (Throwable t) {
            logOnce(packet.getClass(), "Packet " + packet.getClass().getSimpleName() + " failed during playback: " + t);
        } finally {
            dispatching = false;
            dispatchTick = -1;
        }
        chunks.afterHandle(mc);
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            worldReset = true;
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
        float rate = 20.0F * Math.min(1.0F, speed());
        if (manager.tickrate() != rate || manager.isFrozen() != paused) {
            try {
                new ClientboundTickingStatePacket(rate, paused).handle(mc.getConnection());
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
