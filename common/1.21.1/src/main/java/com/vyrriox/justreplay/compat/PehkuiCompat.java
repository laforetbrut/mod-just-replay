package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pehkui entity scales in recordings and replays.
 * <ul>
 *     <li>Pehkui sends an entity's scales ({@code pehkui:scale}) keyed by its entity id, to every player tracking it and
 *     to the player itself, when tracking starts and on each change. These packets are recorded and replayed as they
 *     are: the recording player's actor uses the recording player's entity id, so its own scale lands on the actor.</li>
 *     <li>Snapshots and checkpoints carry the scales every entity has now, since the server sent them before the
 *     capture started.</li>
 *     <li>A scale change handled during a seek is moved forward by the ticks the seek skipped, so a gradual resize is
 *     at the right point at the target.</li>
 * </ul>
 * Gradual resizes run on the entity tick; the optional mixin makes them follow the replay clock. Everything is reached
 * by reflection: nothing here loads a Pehkui class when Pehkui is absent.
 *
 * @author vyrriox
 */
public final class PehkuiCompat {
    private static Reflect reflect;
    private static boolean reflectFailed;

    private PehkuiCompat() {
    }

    private record Reflect(Class<?> payload, Map<?, ?> types, Method getScaleData, Method isReset,
                           Constructor<?> payloadNew, Constructor<?> payloadRead, Method write, Field synced) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.PEHKUI) {
            return reflect;
        }
        try {
            ClassLoader loader = PehkuiCompat.class.getClassLoader();
            Class<?> packet = Class.forName("virtuoel.pehkui.network.ScalePacket", false, loader);
            Class<?> payload = Class.forName("virtuoel.pehkui.network.ScalePayload", false, loader);
            Class<?> registries = Class.forName("virtuoel.pehkui.api.ScaleRegistries", false, loader);
            Class<?> type = Class.forName("virtuoel.pehkui.api.ScaleType", false, loader);
            Class<?> data = Class.forName("virtuoel.pehkui.api.ScaleData", false, loader);
            reflect = new Reflect(payload, (Map<?, ?>) registries.getField("SCALE_TYPES").get(null),
                    type.getMethod("getScaleData", Entity.class), data.getMethod("isReset"),
                    payload.getConstructor(Entity.class, java.util.Collection.class),
                    payload.getConstructor(FriendlyByteBuf.class), packet.getMethod("write", FriendlyByteBuf.class),
                    packet.getField("syncedScales"));
            JustReplay.LOGGER.info("Pehkui detected: entity scales are recorded and replayed, the local player's on its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("pehkui.reflect", "Pehkui support disabled, its classes changed", t);
        }
        return reflect;
    }

    // ---------------------------------------------------------------- recording

    /**
     * Writes the scales an entity has now (snapshots and checkpoints), as the server sends them. Nothing when every
     * scale is at its default. The actor shares the recording player's entity id, so the same packet fits it.
     */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || entity == null) {
            return;
        }
        try {
            List<Object> scales = new ArrayList<>();
            for (Object type : r.types.values()) {
                Object data = r.getScaleData.invoke(type, entity);
                if (data != null && !(boolean) r.isReset.invoke(data)) {
                    scales.add(data);
                }
            }
            if (!scales.isEmpty()) {
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.payloadNew.newInstance(entity, scales)));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("pehkui.snapshot", "Cannot record the Pehkui scales of " + entity, t);
        }
    }

    /** Scales of every entity but players (those come with their player state). */
    static void writeEntities(Iterable<Entity> entities, Consumer<Packet<?>> out) {
        if (reflect() == null) {
            return;
        }
        for (Entity entity : entities) {
            if (!(entity instanceof Player)) {
                writeCurrent(entity, out);
            }
        }
    }

    // ---------------------------------------------------------------- replay

    /**
     * See {@link ReplayCompat#adjustSkipped}: a gradual resize handled {@code late} ticks before the seek target starts
     * that many ticks further on. The recorded packet is copied, never changed.
     */
    static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null || !r.payload.isInstance(custom.payload())) {
            return packet;
        }
        try {
            boolean gradual = false;
            for (Object tag : ((Map<?, ?>) r.synced.get(custom.payload())).values()) {
                CompoundTag scale = (CompoundTag) tag;
                gradual |= scale.getInt("ticks") < scale.getInt("total_ticks");
            }
            if (!gradual) {
                return packet;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            r.write.invoke(custom.payload(), buf);
            Object copy = r.payloadRead.newInstance(buf);
            for (Object tag : ((Map<?, ?>) r.synced.get(copy)).values()) {
                CompoundTag scale = (CompoundTag) tag;
                int total = scale.getInt("total_ticks");
                scale.putInt("ticks", (int) Math.min((long) scale.getInt("ticks") + late, total));
            }
            return new ClientboundCustomPayloadPacket((CustomPacketPayload) copy);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("pehkui.seek", "Cannot move a Pehkui resize to the seek target", t);
            return packet;
        }
    }
}
