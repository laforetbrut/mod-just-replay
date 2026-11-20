package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.BodyParts;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * More Player Models in recordings and replays, through its unofficial NeoForge 1.21.1 port (the mod id
 * {@code moreplayermodels} of the jar {@code kaan-mpm.jar}, a source port of Noppes' 1.21.3 build 20241113). Noppes
 * made no 1.21.1 build. The port keeps the classes of the 1.20.1 mod; its messages are typed payloads, one per kind.
 * <ul>
 *     <li>A player's look (body proportions, added parts, entity model, skin URL, name, animation) is NBT the server
 *     sends once, five seconds after a client starts tracking that player, and again when it changes. Every payload of
 *     the mod has the namespace {@code moreplayermodels}; the ones a client receives name their player by UUID first
 *     (field {@code playerId}), but for the version answer. They are recorded as they are; the ones naming the
 *     recording player are written for its actor, which has a UUID of its own.</li>
 *     <li>Snapshots and checkpoints write the look every player has now, and the actor gets the recording player's
 *     each time it spawns: the server never sends them again.</li>
 *     <li>On a server without the mod no payload carries a look: each client reads the looks from its own files and
 *     from the mod's web service, and animates its own player alone. While a capture runs there, the looks and the
 *     recording player's animations are written as they change.</li>
 *     <li>During a replay the mod's requests to the replay host are dropped, its screens stay closed and its web
 *     service is left alone, so a look only ever comes from the recording. A recorded payload naming the viewer's own
 *     UUID is dropped: the mod would apply it to the camera and save it over the viewer's own look. A payload is
 *     handled once the look the mod keeps on disk for its player has finished loading, which would otherwise
 *     replace it.</li>
 *     <li>The mod draws what it adds to a player (ears, a tail, wings, the item on the back) in layers of its own,
 *     which do not look at the body parts a replay project hid. Two optional mixins make them: an added part goes
 *     with the body part it is attached to, the back item with the body.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads a More Player Models class when the mod is absent.
 *
 * @author vyrriox
 */
public final class MorePlayerModelsCompat {
    /** The namespace of every More Player Models payload. */
    private static final String NAMESPACE = "moreplayermodels";
    private static final String CLIENT_PACKAGE = "noppes.mpm.packets.client.";
    private static final String SCREEN_PACKAGE = "noppes.mpm.client.gui.";
    private static final String BACK_ITEM_LAYER = "noppes.mpm.client.layer.LayerBackItem";
    /** A payload names its player by this field (a record component for the back item). */
    private static final String PLAYER_ID = "playerId";
    /** The codec each payload of the mod declares, which writes the UUID of the player first. */
    private static final String STREAM_CODEC = "STREAM_CODEC";
    private static final int UUID_BYTES = 16;
    /** Move animations each client works out from a player's motion; any other holds until something ends it. */
    private static final Set<String> COMPUTED_MOVES = Set.of("WALK", "IDLE", "FLY", "FLY_IDLE", "SWIM");
    /** Keys of a look that change without the look changing: the edit time and the animation in progress. */
    private static final Set<String> VOLATILE_KEYS = Set.of("LastEdited", "Animation", "MoveAnimation");
    /** Client ticks between two readings of the other players' looks, on a server without the mod. */
    private static final int OTHERS_INTERVAL = 20;
    private static final long CACHE_WAIT_MS = 250;
    private static final Object NONE = new Object();

    private static Reflect reflect;
    private static boolean reflectFailed;
    /** By payload class: its playerId field or accessor, or NONE (the version answer). Network and client threads. */
    private static final Map<Class<?>, Object> NAMED_BY = new ConcurrentHashMap<>();
    /** By payload class: its STREAM_CODEC, or NONE. */
    private static final Map<Class<?>, Object> CODECS = new ConcurrentHashMap<>();

    /** What the current capture already holds of the looks no payload carries. Client thread only. */
    private static Object polledFor;
    private static Object localData;
    private static long localEdited;
    private static Object localAnimation;
    private static Object localMove;
    private static int othersWait;
    private static boolean pollFailed;
    private static final Map<Object, Long> OTHERS_EDITED = new WeakHashMap<>();

    /** Looks whose file on disk has finished loading in this replay, by the mod's data object of a player entity. */
    private static final Map<Object, Boolean> CACHE_LOADED = new WeakHashMap<>();
    private static boolean cacheWaitFailed;
    private static ReplaySession uuidsOf;
    private static UUID replayRecorder;
    private static UUID replayActor;

    private static Object webApi;
    private static Field webRunning;
    private static boolean webFailed;
    private static boolean webHeld;

    /** MpmPart.bodyPart: the body part an added part is attached to. */
    private static Field partBody;
    private static boolean partBodyFailed;

    private MorePlayerModelsCompat() {
    }

    private record Reflect(Method get, Method writeToNbt, Field lastEdited, Field animation, Field moveAnimation,
                           Field backItem, Field saveExecutor, Field hasServerSide, Constructor<?> dataNew,
                           Constructor<?> backItemNew, Constructor<?> animationNew) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !Integrations.MORE_PLAYER_MODELS.on()) {
            return reflect;
        }
        try {
            ClassLoader loader = MorePlayerModelsCompat.class.getClassLoader();
            Class<?> data = Class.forName("noppes.mpm.ModelData", false, loader);
            Class<?> mod = Class.forName("noppes.mpm.MorePlayerModels", false, loader);
            Class<?> animation = Class.forName("noppes.mpm.constants.EnumAnimation", false, loader);
            Class<?> dataSend = Class.forName(CLIENT_PACKAGE + "PacketPlayerDataSend", false, loader);
            Class<?> backItem = Class.forName(CLIENT_PACKAGE + "PacketBackItemUpdate", false, loader);
            Class<?> animationStart = Class.forName(CLIENT_PACKAGE + "PacketAnimationStart", false, loader);
            for (Class<?> payload : new Class<?>[]{dataSend, backItem, animationStart}) {
                if (!CustomPacketPayload.class.isAssignableFrom(payload) || playerIdOf(payload) == NONE || codecOf(payload) == null) {
                    throw new IllegalStateException(payload.getName() + " is not a payload naming its player");
                }
            }
            reflect = new Reflect(data.getMethod("get", Player.class), data.getMethod("writeToNBT"),
                    data.getField("lastEdited"), data.getField("animation"), data.getField("moveAnimation"),
                    data.getField("backItem"), data.getField("saveExecutor"), mod.getField("HasServerSide"),
                    dataSend.getConstructor(UUID.class, CompoundTag.class),
                    backItem.getConstructor(UUID.class, ItemStack.class),
                    animationStart.getConstructor(UUID.class, animation));
            JustReplay.LOGGER.info("More Player Models detected: player looks are recorded and replayed, the local player's on its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            Integrations.MORE_PLAYER_MODELS.fail("reflect", t);
        }
        return reflect;
    }

    /** The payload of a clientbound message of the mod, or null for any other packet. */
    private static CustomPacketPayload messageOf(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom && NAMESPACE.equals(custom.payload().type().id().getNamespace())) {
            return custom.payload();
        }
        return null;
    }

    /** The playerId field or accessor of a payload class, or NONE. */
    private static Object playerIdOf(Class<?> type) {
        return NAMED_BY.computeIfAbsent(type, t -> {
            try {
                Field field = t.getField(PLAYER_ID);
                if (field.getType() == UUID.class) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // A record: its component has an accessor.
            }
            try {
                Method accessor = t.getMethod(PLAYER_ID);
                if (accessor.getReturnType() == UUID.class) {
                    return accessor;
                }
            } catch (NoSuchMethodException ignored) {
                // The version answer names no player.
            }
            return NONE;
        });
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codecOf(Class<?> type) {
        Object codec = CODECS.computeIfAbsent(type, t -> {
            try {
                Object value = t.getField(STREAM_CODEC).get(null);
                return value instanceof StreamCodec<?, ?> ? value : NONE;
            } catch (ReflectiveOperationException e) {
                return NONE;
            }
        });
        return codec == NONE ? null : (StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload>) codec;
    }

    /** The player a payload is about, or null for the version answer, the only one that names none. */
    private static UUID named(CustomPacketPayload payload) throws ReflectiveOperationException {
        Object member = playerIdOf(payload.getClass());
        if (member instanceof Field field) {
            return (UUID) field.get(payload);
        }
        if (member instanceof Method accessor) {
            return (UUID) accessor.invoke(payload);
        }
        return null;
    }

    /**
     * The same payload about another player: encoded by its own codec, its first sixteen bytes (the UUID of the player
     * it names) replaced, decoded again. Null when that cannot be done (no connection to read registries from).
     */
    private static Packet<?> retarget(CustomPacketPayload payload, UUID from, UUID to) {
        StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codec = codecOf(payload.getClass());
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (codec == null || listener == null) {
            return null;
        }
        RegistryFriendlyByteBuf bytes = ClientPlatform.get().bufDecorator(listener).apply(Unpooled.buffer());
        try {
            codec.encode(bytes, payload);
            int start = bytes.readerIndex();
            if (bytes.readableBytes() < UUID_BYTES || bytes.getLong(start) != from.getMostSignificantBits()
                    || bytes.getLong(start + 8) != from.getLeastSignificantBits()) {
                throw new IllegalStateException(payload.type().id() + " does not start with the UUID of its player");
            }
            bytes.setLong(start, to.getMostSignificantBits());
            bytes.setLong(start + 8, to.getLeastSignificantBits());
            return new ClientboundCustomPayloadPacket(codec.decode(bytes));
        } finally {
            bytes.release();
        }
    }

    /** A payload of the mod as the clientbound packet its server sends. */
    private static Packet<?> packet(Object payload) {
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) payload);
    }

    private static void writeLook(Reflect r, UUID target, Object data, Consumer<Packet<?>> out) throws ReflectiveOperationException {
        out.accept(packet(r.dataNew.newInstance(target, r.writeToNbt.invoke(data))));
    }

    // ---------------------------------------------------------------- recording

    /**
     * See {@link ReplayCompat#rewriteRecorded}: a payload about the recording player (the server sends a player its
     * own look, animations and back item under its real UUID) is written about its actor. Any thread.
     */
    static Packet<?> rewriteRecorded(Packet<?> packet, UUID local, UUID actor) throws ReflectiveOperationException {
        CustomPacketPayload payload = local == null || actor == null ? null : messageOf(packet);
        if (payload == null || !local.equals(named(payload))) {
            return packet;
        }
        Packet<?> rewritten = retarget(payload, local, actor);
        // Without a connection the replay still hands it to the actor, as for a recording made before this.
        return rewritten != null ? rewritten : packet;
    }

    /**
     * The look a player has now, and the item on its back, as the server sends them, about the player with this UUID
     * in the replay (snapshots, checkpoints, the actor's spawn). The animation in progress is part of the look.
     */
    static void writeCurrent(Player player, UUID target, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || player == null || target == null) {
            return;
        }
        try {
            Object data = r.get.invoke(null, player);
            writeLook(r, target, data, out);
            if (r.backItem.get(data) instanceof ItemStack stack && !stack.isEmpty()) {
                out.accept(packet(r.backItemNew.newInstance(target, stack.copy())));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("mpm.snapshot", "Cannot record the More Player Models look of a player", t);
        }
    }

    /** The actor just spawned in the recording itself: its look is written and counts as recorded from here. */
    static void writeActorForm(Player player, UUID actor, Consumer<Packet<?>> out) {
        writeCurrent(player, actor, out);
        Reflect r = reflect();
        if (r == null || player == null || pollFailed) {
            return;
        }
        try {
            Object data = r.get.invoke(null, player);
            polledFor = Recorder.capturing();
            localData = data;
            localEdited = r.lastEdited.getLong(data);
            localAnimation = r.animation.get(data);
            localMove = r.moveAnimation.get(data);
        } catch (Throwable t) {
            pollFailed = true;
            ReplayCompat.warnOnce("mpm.poll", "Cannot follow the More Player Models looks no payload carries", t);
        }
    }

    /**
     * See {@link ReplayCompat#writeLiveChanges}. With the mod on the server every change comes in a payload, the
     * recording player's included. Without it, the recording player's look and animations are written for its actor
     * when they change, and the other players' looks about once a second (read from the client's files and from the
     * mod's web service, they change on a thread of the mod).
     */
    static void writeLiveChanges(Minecraft mc, LocalPlayer player, UUID actor, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || pollFailed || mc.level == null || player == null || actor == null) {
            return;
        }
        try {
            Object capture = Recorder.capturing();
            if (capture != polledFor) {
                polledFor = capture;
                localData = null;
                OTHERS_EDITED.clear();
                othersWait = 0;
            }
            if (r.hasServerSide.getBoolean(null)) {
                return;
            }
            Object data = r.get.invoke(null, player);
            long edited = r.lastEdited.getLong(data);
            Object animation = r.animation.get(data);
            Object move = r.moveAnimation.get(data);
            if (data != localData || edited != localEdited && animation == localAnimation) {
                // A new player entity, or an edited look. The look carries the animations with it.
                writeLook(r, actor, data, out);
            } else {
                if (animation != localAnimation) {
                    out.accept(packet(r.animationNew.newInstance(actor, animation)));
                }
                if (move != localMove && (isHeld(move) || isHeld(localMove))) {
                    // Sitting, crawling, lying down, and getting up again: the actor cannot work those out itself.
                    out.accept(packet(r.animationNew.newInstance(actor, move)));
                }
            }
            localData = data;
            localEdited = edited;
            localAnimation = animation;
            localMove = move;
            if (--othersWait > 0) {
                return;
            }
            othersWait = OTHERS_INTERVAL;
            for (Player other : mc.level.players()) {
                if (other == player) {
                    continue;
                }
                Object otherData = r.get.invoke(null, other);
                long otherEdited = r.lastEdited.getLong(otherData);
                Long known = OTHERS_EDITED.put(otherData, otherEdited);
                if (known == null || known != otherEdited) {
                    writeLook(r, other.getUUID(), otherData, out);
                }
            }
        } catch (Throwable t) {
            pollFailed = true;
            ReplayCompat.warnOnce("mpm.poll", "Cannot follow the More Player Models looks no payload carries", t);
        }
    }

    private static boolean isHeld(Object moveAnimation) {
        return moveAnimation instanceof Enum<?> value && !COMPUTED_MOVES.contains(value.name());
    }

    // ---------------------------------------------------------------- replay

    /**
     * See {@link ReplayCompat#filterPlayback}. A payload about the recording player in a recording made before this
     * integration is handled about its actor. One about the viewer's own UUID is dropped: the mod looks the player up
     * by UUID, finds the camera first, and saves what it applies as the viewer's own look.
     */
    static Packet<?> filterPlayback(Packet<?> packet) throws ReflectiveOperationException {
        CustomPacketPayload payload = messageOf(packet);
        UUID named = payload == null ? null : named(payload);
        if (named == null) {
            return packet;
        }
        Minecraft mc = Minecraft.getInstance();
        readSessionUuids();
        if (replayActor != null && named.equals(replayRecorder)) {
            Packet<?> rewritten = retarget(payload, named, replayActor);
            if (rewritten == null) {
                return null;
            }
            packet = rewritten;
            named = replayActor;
        } else if (mc.player != null && named.equals(mc.player.getUUID())) {
            return null;
        }
        awaitCachedLook(mc, named);
        return packet;
    }

    private static void readSessionUuids() {
        ReplaySession session = ReplaySession.current();
        if (session == uuidsOf) {
            return;
        }
        uuidsOf = session;
        replayRecorder = session == null ? null : parse(session.meta().recorderUuid);
        replayActor = session == null ? null : parse(session.meta().actorUuid);
    }

    private static UUID parse(String uuid) {
        try {
            return uuid == null || uuid.isEmpty() ? null : UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The mod keeps the last look it saw of each player in a file, and loads it on a thread of its own the first time
     * it is asked about a player entity. A payload handled before that load ends is overwritten by the file: the load
     * is started and awaited here first, once per player entity.
     */
    private static void awaitCachedLook(Minecraft mc, UUID named) {
        Reflect r = reflect();
        if (r == null || cacheWaitFailed || mc.level == null) {
            return;
        }
        Player player = mc.level.getPlayerByUUID(named);
        if (player == null) {
            return;
        }
        try {
            Object data = r.get.invoke(null, player);
            if (CACHE_LOADED.put(data, Boolean.TRUE) == null) {
                // The mod reads and writes its files one at a time: a task behind the load ends after it.
                ((ExecutorService) r.saveExecutor.get(null)).submit(() -> { }).get(CACHE_WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            cacheWaitFailed = true;
            ReplayCompat.warnOnce("mpm.cache", "Cannot wait for the looks More Player Models keeps on disk; one may replace a replayed look", t);
        }
    }

    /** The mod's requests and edits have no server to go to in a replay. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && NAMESPACE.equals(custom.payload().type().id().getNamespace());
    }

    /** True for a screen of the mod: its editor changes the look of the camera, which no replay shows. */
    static boolean isOwnScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith(SCREEN_PACKAGE);
    }

    /** Called when the replay starts, then every client tick of it. */
    static void holdWebSync() {
        setWebSync(true);
    }

    /** The replay closed: the mod asks its web service again. */
    static void onReplayEnd() {
        setWebSync(false);
        CACHE_LOADED.clear();
        uuidsOf = null;
        replayRecorder = null;
        replayActor = null;
    }

    /**
     * Without the mod on the server (and the replay host answers nothing), the mod asks its web service every second
     * for the looks of the players in view, by UUID, and applies the answer: the players of a replay would take the
     * look they have today. The mod starts no request while one is on its way, so that flag is held during the replay.
     */
    private static void setWebSync(boolean held) {
        if (webFailed || !held && !webHeld) {
            return;
        }
        try {
            if (webRunning == null) {
                Class<?> web = Class.forName("noppes.mpm.sync.WebApi", false, MorePlayerModelsCompat.class.getClassLoader());
                webApi = web.getField("instance").get(null);
                webRunning = web.getField("isRunning");
            }
            webRunning.setBoolean(webApi, held);
            webHeld = held;
        } catch (Throwable t) {
            webFailed = true;
            ReplayCompat.warnOnce("mpm.web", "Cannot keep More Player Models from asking its web service during the replay", t);
        }
    }

    // ---------------------------------------------------------------- hidden body parts

    /**
     * Called by the parts mixin for each added part of a player about to be drawn: true when the replay project hid
     * the body part it is attached to. A part that spans both arms or both legs goes when both are hidden.
     */
    public static boolean hidesPart(Entity player, Object part) {
        if (partBodyFailed || part == null || !ReplaySession.isRunning()) {
            return false;
        }
        int mask = BodyParts.hidden(player);
        if (mask == 0) {
            return false;
        }
        try {
            if (partBody == null) {
                partBody = Class.forName("noppes.mpm.client.parts.MpmPart", false, MorePlayerModelsCompat.class.getClassLoader())
                        .getField("bodyPart");
            }
            int bits = partBody.get(part) instanceof Enum<?> body ? bitsOf(body.name()) : 0;
            return bits != 0 && (mask & bits) == bits;
        } catch (Throwable t) {
            partBodyFailed = true;
            ReplayCompat.warnOnce("mpm.parts", "Cannot tell which body part a More Player Models part is attached to; "
                    + "added parts stay on hidden body parts", t);
            return false;
        }
    }

    /** The body parts of the replay editor that a body part of the mod stands for; 0 for a part attached to none. */
    private static int bitsOf(String bodyPart) {
        return switch (bodyPart) {
            case "HEAD" -> BodyParts.HEAD;
            case "BODY" -> BodyParts.BODY;
            case "ARMS" -> BodyParts.LEFT_ARM | BodyParts.RIGHT_ARM;
            case "LEFT_ARM" -> BodyParts.LEFT_ARM;
            case "RIGHT_ARM" -> BodyParts.RIGHT_ARM;
            case "LEGS" -> BodyParts.LEFT_LEG | BodyParts.RIGHT_LEG;
            case "LEFT_LEG" -> BodyParts.LEFT_LEG;
            case "RIGHT_LEG" -> BodyParts.RIGHT_LEG;
            default -> 0;
        };
    }

    /** Called by the layer mixin for each layer of the mod about to draw on a player: the back item goes with a hidden body. */
    public static boolean hidesLayer(Object layer, Entity player) {
        return ReplaySession.isRunning() && BACK_ITEM_LAYER.equals(layer.getClass().getName())
                && (BodyParts.hidden(player) & BodyParts.BODY) != 0;
    }

    // ---------------------------------------------------------------- self test

    /** The look of a player without what changes on its own, as text; null when the mod is absent or unreadable. */
    public static String lookOf(Player player) {
        Reflect r = reflect();
        if (r == null || player == null) {
            return null;
        }
        try {
            CompoundTag look = ((CompoundTag) r.writeToNbt.invoke(r.get.invoke(null, player))).copy();
            VOLATILE_KEYS.forEach(look::remove);
            return look.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True while the mod's web service is kept quiet; null when that cannot be read. */
    public static Boolean webSyncHeld() {
        try {
            return webRunning == null ? null : webRunning.getBoolean(webApi);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The recorded payload of the mod about one player, rewritten for another as the recorder writes it: the UUID of
     * the payload it gives back, or null when nothing was rewritten. Proves the codec route in a real client.
     */
    public static UUID retargetedLook(Player player, UUID to) {
        Reflect r = reflect();
        if (r == null || player == null || to == null) {
            return null;
        }
        try {
            Object payload = r.dataNew.newInstance(player.getUUID(), r.writeToNbt.invoke(r.get.invoke(null, player)));
            Packet<?> rewritten = rewriteRecorded(packet(payload), player.getUUID(), to);
            CustomPacketPayload out = rewritten instanceof ClientboundCustomPayloadPacket custom ? custom.payload() : null;
            return out == null || out == payload ? null : named(out);
        } catch (Throwable t) {
            return null;
        }
    }
}
