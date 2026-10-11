package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
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

    private final List<RecordedPacket> packets;
    private final int duration;
    private final Set<Class<?>> failedTypes = Collections.synchronizedSet(new HashSet<>());
    private PacketCodec codec;
    private int index;
    private int cursor;
    private boolean paused;
    private int speedIndex = 3;
    private boolean worldReset;
    private int handledInLastFlush;

    public Playback(List<RecordedPacket> packets, int durationTicks) {
        this.packets = packets;
        int last = packets.isEmpty() ? 0 : packets.get(packets.size() - 1).tick();
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
        index = 0;
        cursor = 0;
        flushTo(0);
    }

    public void tick(Minecraft mc) {
        if (codec == null) {
            return;
        }
        if (!paused && !mc.isPaused()) {
            if (cursor < duration) {
                cursor++;
                flushTo(cursor);
            } else {
                paused = true;
            }
        }
        applyTickRate(mc);
    }

    /** Jumps to a tick. Going back replays the stream from its start, which rebuilds the world exactly. */
    public void seek(int target) {
        target = Math.max(0, Math.min(duration, target));
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Vec3 camPos = player != null ? player.position() : null;
        float yaw = player != null ? player.getYRot() : 0;
        float pitch = player != null ? player.getXRot() : 0;
        if (target < cursor) {
            index = 0;
            cursor = 0;
            mc.gui.getChat().clearMessages(false);
        }
        flushTo(target);
        cursor = target;
        if (camPos != null && mc.player != null) {
            placeCamera(mc.player, camPos, yaw, pitch);
        }
        applyTickRate(mc);
    }

    private void flushTo(int tick) {
        Minecraft mc = Minecraft.getInstance();
        handledInLastFlush = 0;
        while (index < packets.size() && packets.get(index).tick() <= tick) {
            handle(mc, packets.get(index++));
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
        if (PacketFilter.shouldDrop(packet)) {
            return;
        }
        if (packet instanceof ClientboundLoginPacket login) {
            packet = rewriteLogin(login);
        }
        try {
            ((Packet<ClientGamePacketListener>) packet).handle(listener);
        } catch (Throwable t) {
            logOnce(packet.getClass(), "Packet " + packet.getClass().getSimpleName() + " failed during playback: " + t);
        }
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            worldReset = true;
            enforceCamera(mc);
        }
    }

    private static ClientboundLoginPacket rewriteLogin(ClientboundLoginPacket login) {
        CommonPlayerSpawnInfo s = login.commonPlayerSpawnInfo();
        CommonPlayerSpawnInfo spawn = new CommonPlayerSpawnInfo(s.dimensionType(), s.dimension(), s.seed(), GameType.SPECTATOR,
                s.previousGameType(), s.isDebug(), s.isFlat(), s.lastDeathLocation(), s.portalCooldown());
        return new ClientboundLoginPacket(CAMERA_ENTITY_ID, login.hardcore(), login.levels(), login.maxPlayers(), login.chunkRadius(),
                login.simulationDistance(), false, false, login.doLimitedCrafting(), spawn, false);
    }

    /** Keeps the local player a free flying spectator, whatever the recorded packets did. */
    public static void enforceCamera(Minecraft mc) {
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            mc.gameMode.setLocalMode(GameType.SPECTATOR);
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
        float rate = 20.0F * speed();
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
