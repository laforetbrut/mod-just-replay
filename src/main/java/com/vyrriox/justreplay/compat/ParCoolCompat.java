package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ParCool parkour actions (vault, roll, dodge, wall run, crawl, cling...) in recordings and replays.
 * <ul>
 *     <li>A client sends its action changes to the server ({@code parcool:action.set}); the server sends them to every
 *     player, batched in {@code parcool:action.compose}, with the player's UUID. Clients look the player up by UUID and
 *     run the action on it, which plays its animation. Actions of other players are recorded and replayed as they are.</li>
 *     <li>The recording player receives its own actions back too, flagged as sent by itself and ignored. Those are
 *     written into the recording for its actor's UUID instead, as another client receives them.</li>
 *     <li>Checkpoints and mid-session snapshots carry the lasting actions each player is doing, so the actor is in the
 *     right action after a seek.</li>
 *     <li>During a replay, ParCool's requests to the replay host are dropped.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads a ParCool class when ParCool is absent.
 *
 * @author vyrriox
 */
public final class ParCoolCompat {
    private static final String NAMESPACE = "parcool";
    private static Reflect reflect;
    private static boolean reflectFailed;
    /** Action changes of the recording player written for its actor, and the action starts among them. */
    private static final AtomicInteger LOCAL_CHANGES = new AtomicInteger();
    private static final AtomicInteger LOCAL_STARTS = new AtomicInteger();

    private ParCoolCompat() {
    }

    private record Reflect(Class<?> setPacket, Class<?> multiPacket, Method fromServer, Method add, Field msgList,
                           Field playerId, Constructor<?> multiNew, Constructor<?> statePacketNew, Method entries,
                           Constructor<?> entryNew, Method entryType, Object start, Method parkourability,
                           Method actions, Class<?> continuable, Method isDoing, Method actionEntry, Method entryId,
                           Method syncData, Field holderData, Method propertyValue, Method propertyHandler,
                           Method handlerWrite, Method handlerLength) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.PARCOOL) {
            return reflect;
        }
        try {
            ClassLoader loader = ParCoolCompat.class.getClassLoader();
            Class<?> composable = Class.forName("com.alrex.parcool.common.network.MultiComposablePacket", false, loader);
            Class<?> setPacket = Class.forName("com.alrex.parcool.common.network.ActionStateSetPacket", false, loader);
            Class<?> multiPacket = Class.forName("com.alrex.parcool.common.network.MultiActionStateSetPacket", false, loader);
            Class<?> statePacket = Class.forName("com.alrex.parcool.common.network.ActionStatePacket", false, loader);
            Class<?> entry = Class.forName("com.alrex.parcool.common.network.ActionStatePacket$Entry", false, loader);
            Class<?> type = Class.forName("com.alrex.parcool.common.network.ActionStatePacket$Type", false, loader);
            Class<?> actionEntry = Class.forName("com.alrex.parcool.api.action.ActionEntry", false, loader);
            Class<?> parkourability = Class.forName("com.alrex.parcool.common.Parkourability", false, loader);
            Class<?> action = Class.forName("com.alrex.parcool.api.action.Action", false, loader);
            Class<?> continuable = Class.forName("com.alrex.parcool.api.action.ContinuableAction", false, loader);
            Class<?> holder = Class.forName("com.alrex.parcool.api.action.SynchronizedDataHolder", false, loader);
            Class<?> property = Class.forName("com.alrex.parcool.api.action.SynchronizedProperty", false, loader);
            Class<?> handler = Class.forName("com.alrex.parcool.api.action.SynchronizedProperty$IHandler", false, loader);
            Object start = null;
            for (Object constant : type.getEnumConstants()) {
                if ("START".equals(((Enum<?>) constant).name())) {
                    start = constant;
                }
            }
            Field msgList = composable.getDeclaredField("msgList");
            Field playerId = setPacket.getDeclaredField("playerID");
            Field holderData = holder.getDeclaredField("data");
            Method propertyHandler = property.getDeclaredMethod("getHandler");
            Method handlerWrite = handler.getMethod("write", ByteBuffer.class, Object.class);
            Method handlerLength = handler.getMethod("dataLengthInBytes");
            for (java.lang.reflect.AccessibleObject member : new java.lang.reflect.AccessibleObject[]{msgList, playerId, holderData,
                    propertyHandler, handlerWrite, handlerLength}) {
                member.setAccessible(true);
            }
            reflect = new Reflect(setPacket, multiPacket, setPacket.getMethod("fromServer", UUID.class),
                    composable.getMethod("add", CustomPacketPayload.class), msgList, playerId, multiPacket.getConstructor(),
                    statePacket.getConstructor(String.class, List.class), statePacket.getMethod("entries"),
                    entry.getConstructor(type, actionEntry, byte[].class), entry.getMethod("type"), start,
                    parkourability.getMethod("get", Player.class), parkourability.getMethod("getActions"), continuable,
                    continuable.getMethod("isDoing"), action.getMethod("getEntry"), actionEntry.getMethod("id"),
                    action.getMethod("getSynchronizedData"), holderData, property.getMethod("get"), propertyHandler,
                    handlerWrite, handlerLength);
            if (start == null) {
                throw new IllegalStateException("no START action state");
            }
            JustReplay.LOGGER.info("ParCool detected: the local player's parkour actions are recorded for its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("parcool.reflect", "ParCool support disabled, its classes changed", t);
        }
        return reflect;
    }

    // ---------------------------------------------------------------- recording

    /**
     * See {@link ReplayCompat#rewriteRecorded}: the echo of the recording player's own actions is written for its
     * actor (sent by the server, not by itself). Other players' actions are kept as they are.
     */
    static Packet<?> rewriteRecorded(Packet<?> packet, UUID localUuid, UUID actorUuid) throws Exception {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !NAMESPACE.equals(custom.payload().type().id().getNamespace())) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        if (r.setPacket.isInstance(payload)) {
            CustomPacketPayload rewritten = rewriteSet(r, payload, localUuid, actorUuid);
            return rewritten == payload ? packet : new ClientboundCustomPayloadPacket(rewritten);
        }
        if (!r.multiPacket.isInstance(payload)) {
            return packet;
        }
        Collection<?> sets = (Collection<?>) r.msgList.get(payload);
        boolean changed = false;
        List<CustomPacketPayload> out = new ArrayList<>(sets.size());
        for (Object set : sets) {
            CustomPacketPayload rewritten = rewriteSet(r, (CustomPacketPayload) set, localUuid, actorUuid);
            changed |= rewritten != set;
            out.add(rewritten);
        }
        if (!changed) {
            return packet;
        }
        Object multi = r.multiNew.newInstance();
        for (CustomPacketPayload set : out) {
            r.add.invoke(multi, set);
        }
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) multi);
    }

    /** One player's action changes, aimed at the actor when they belong to the recording player. */
    private static CustomPacketPayload rewriteSet(Reflect r, CustomPacketPayload set, UUID localUuid, UUID actorUuid) throws Exception {
        if (!localUuid.equals(r.playerId.get(set))) {
            return set;
        }
        Object copy = r.fromServer.invoke(null, actorUuid);
        for (Object state : (Collection<?>) r.msgList.get(set)) {
            r.add.invoke(copy, state);
            for (Object entry : (List<?>) r.entries.invoke(state)) {
                LOCAL_CHANGES.incrementAndGet();
                if (r.entryType.invoke(entry) == r.start) {
                    LOCAL_STARTS.incrementAndGet();
                }
            }
        }
        return (CustomPacketPayload) copy;
    }

    /**
     * Writes the lasting actions a player is doing now (snapshots and checkpoints) as their start, received from the
     * server, for the player with this UUID in the replay.
     */
    static void writeCurrent(Player player, UUID target, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || player == null) {
            return;
        }
        try {
            Object parkourability = r.parkourability.invoke(null, player);
            if (parkourability == null) {
                return;
            }
            Map<String, List<Object>> groups = new LinkedHashMap<>();
            for (Object action : (Iterable<?>) r.actions.invoke(parkourability)) {
                if (!r.continuable.isInstance(action) || !(boolean) r.isDoing.invoke(action)) {
                    continue;
                }
                Object entry = r.actionEntry.invoke(action);
                ResourceLocation id = (ResourceLocation) r.entryId.invoke(entry);
                groups.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>())
                        .add(r.entryNew.newInstance(r.start, entry, syncedData(r, action)));
            }
            if (groups.isEmpty()) {
                return;
            }
            Object set = r.fromServer.invoke(null, target);
            for (Map.Entry<String, List<Object>> group : groups.entrySet()) {
                r.add.invoke(set, r.statePacketNew.newInstance(group.getKey(), group.getValue()));
            }
            out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) set));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("parcool.snapshot", "Cannot record the ParCool actions of " + player.getName().getString(), t);
        }
    }

    /**
     * The synchronized values of an action (direction, duration...) in the layout ParCool reads when an action starts:
     * each value is its index then its bytes. Read without touching the values' change flags, so ParCool's own
     * synchronization is unaffected. Empty when they cannot be read: the action then starts with its default values.
     */
    private static byte[] syncedData(Reflect r, Object action) {
        try {
            Object holder = r.syncData.invoke(action);
            Object[] properties = holder == null ? null : (Object[]) r.holderData.get(holder);
            if (properties == null || properties.length == 0) {
                return new byte[0];
            }
            int size = 0;
            Object[] handlers = new Object[properties.length];
            for (int i = 0; i < properties.length; i++) {
                handlers[i] = r.propertyHandler.invoke(properties[i]);
                size += 1 + (byte) r.handlerLength.invoke(handlers[i]);
            }
            ByteBuffer buffer = ByteBuffer.allocate(size);
            for (int i = 0; i < properties.length; i++) {
                Object value = r.propertyValue.invoke(properties[i]);
                if (value == null) {
                    continue;
                }
                buffer.put((byte) i);
                r.handlerWrite.invoke(handlers[i], buffer, value);
            }
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            return data;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("parcool.data", "Cannot read the ParCool action values, actions resume with default values", t);
            return new byte[0];
        }
    }

    // ---------------------------------------------------------------- replay

    /** ParCool's requests to the replay host (action changes, stamina) have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && NAMESPACE.equals(custom.payload().type().id().getNamespace());
    }

    // ---------------------------------------------------------------- diagnostics

    /** Action changes of the recording player written for its actor since the game started. */
    public static int localChanges() {
        return LOCAL_CHANGES.get();
    }

    /** Action starts among {@link #localChanges()}. */
    public static int localStarts() {
        return LOCAL_STARTS.get();
    }

    /** The lasting actions an entity is doing ("crawl,wall_slide"), "none", or "n/a" without ParCool. */
    public static String describe(Entity entity) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof Player player)) {
            return "n/a";
        }
        try {
            Object parkourability = r.parkourability.invoke(null, player);
            if (parkourability == null) {
                return "none";
            }
            StringBuilder doing = new StringBuilder();
            for (Object action : (Iterable<?>) r.actions.invoke(parkourability)) {
                if (r.continuable.isInstance(action) && (boolean) r.isDoing.invoke(action)) {
                    if (!doing.isEmpty()) {
                        doing.append(',');
                    }
                    doing.append(((ResourceLocation) r.entryId.invoke(r.actionEntry.invoke(action))).getPath());
                }
            }
            return doing.isEmpty() ? "none" : doing.toString();
        } catch (Throwable t) {
            return "error " + (t.getCause() != null ? t.getCause() : t);
        }
    }
}
