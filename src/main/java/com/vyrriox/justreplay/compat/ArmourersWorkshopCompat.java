package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Armourer's Workshop skins (outfits, weapons) in recordings and replays.
 * <ul>
 *     <li>Every Armourer's Workshop packet rides one custom payload channel, split in chunks. Wardrobe syncs name the
 *     entity by id, and the recording player's actor shares the recording player's id, so its own wardrobe lands on
 *     the actor as recorded.</li>
 *     <li>Skins are loaded by one loader shared by the whole game (client and integrated server). On a dedicated
 *     server the client asks the server for each skin, or reads its own disk cache. In singleplayer the loader reads
 *     the world's skin database directly, so no skin ever crosses the connection. Every skin the loader finishes
 *     during a capture is therefore recorded as a skin answer, whatever its source. Snapshots and checkpoints add
 *     every skin the loader holds, every skin a wardrobe or an equipped item references (loaded from the world's
 *     database in singleplayer), and the wardrobe of every entity.</li>
 *     <li>During a replay the replay world's own database is empty. Recorded skin answers fill the loader directly,
 *     the skins stay held for the whole replay, and any lookup of a skin the replay holds is served from it before the
 *     loader reaches a database, a server or the network. Lookups of server skins the replay has not played yet wait
 *     for their answer instead of failing.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Armourer's Workshop class when the mod is absent.
 *
 * @author vyrriox
 */
public final class ArmourersWorkshopCompat {
    private static final String PKG = "moe.plushie.armourers_workshop.";
    /** Data domains held by a server: in a replay their lookups must never reach the replay world. */
    private static final Set<String> SERVER_DOMAINS = Set.of("DATABASE", "DATABASE_LINK", "DEDICATED_SERVER");
    /** Skins played in the running replay, held so the mod's soft cache never drops them. */
    private static final Map<String, Object> REPLAY_SKINS = new ConcurrentHashMap<>();
    /** Skins already in the current capture, and skins already reported missing from it. */
    private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPLAY_MISSING = ConcurrentHashMap.newKeySet();
    private static volatile Object recordedFor;
    private static volatile Thread snapshotThread;
    private static int lastLoggedCount = -1;
    private static Reflect reflect;
    private static boolean reflectFailed;
    /** CustomPacket.packetId(), CustomPacket.encode(IFriendlyByteBuf) and IFriendlyByteBuf.wrap(ByteBuf). */
    private static Method packetId;
    private static Method packetEncode;
    private static Method bufWrap;
    /** Largest Armourer's Workshop part in one payload, below the clientbound custom payload limit. */
    private static final int PART_SIZE = 900_000;
    private static final int SPLIT_BODY = -1;
    private static final int SPLIT_BEGIN = -2;
    private static final int SPLIT_END = -3;

    private ArmourersWorkshopCompat() {
    }

    private record Reflect(Object loader, Field entries, Method entryGet, Method entryCompleted, Method entryAccept,
                           Field entryId, Method addSkin, Method loadSync, Method loadAsync,
                           Constructor<?> responseNew, Field responseId, Field responseSkin, Field responseCompress,
                           Class<?> requestPacket, Field requestId, Object dispatcher, Method split,
                           Class<?> distributor, Constructor<?> proxyNew, Method wardrobeOf, Method wardrobeSync,
                           Method wardrobeInventory, Method descriptorOf, Method descriptorEmpty, Method descriptorId,
                           Method domainByName) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.ARMOURERS_WORKSHOP) {
            return reflect;
        }
        try {
            ClassLoader cl = ArmourersWorkshopCompat.class.getClassLoader();
            Class<?> loaderClass = Class.forName(PKG + "core.skin.SkinLoader", false, cl);
            Class<?> entryClass = Class.forName(PKG + "core.skin.SkinLoader$Entry", false, cl);
            Class<?> skinClass = Class.forName(PKG + "core.skin.Skin", false, cl);
            Class<?> descriptor = Class.forName(PKG + "core.skin.SkinDescriptor", false, cl);
            Class<?> domain = Class.forName(PKG + "core.data.DataDomain", false, cl);
            Class<?> resultHandler = Class.forName(PKG + "api.core.IResultHandler", false, cl);
            Class<?> customPacket = Class.forName(PKG + "core.network.CustomPacket", false, cl);
            Class<?> response = Class.forName(PKG + "core.network.ResponseSkinPacket", false, cl);
            Class<?> request = Class.forName(PKG + "core.network.RequestSkinPacket", false, cl);
            Class<?> network = Class.forName(PKG + "init.platform.NetworkManager", false, cl);
            Class<?> dispatcherClass = Class.forName(PKG + "init.platform.NetworkManager$Dispatcher", false, cl);
            Class<?> distributor = Class.forName(PKG + "init.platform.NetworkManager$Distributor", false, cl);
            Class<?> buf = Class.forName(PKG + "api.network.IFriendlyByteBuf", false, cl);
            String proxyName = "fabric".equals(com.vyrriox.justreplay.platform.Platform.get().loaderName())
                    ? "compat.fabric.core.AbstractFabricNetwork$Proxy" : "compat.forge.AbstractForgeNetwork$Proxy";
            Class<?> proxy = Class.forName(PKG + proxyName, false, cl);
            Class<?> wardrobe = Class.forName(PKG + "core.capability.SkinWardrobe", false, cl);
            Class<?> wardrobePacket = Class.forName(PKG + "core.network.UpdateWardrobePacket", false, cl);

            packetId = customPacket.getMethod("packetId");
            packetEncode = customPacket.getMethod("encode", buf);
            bufWrap = buf.getMethod("wrap", io.netty.buffer.ByteBuf.class);
            Field entries = accessible(loaderClass.getDeclaredField("entries"));
            Field dispatcherField = accessible(network.getDeclaredField("dispatcher"));
            Object dispatcher = dispatcherField.get(null);
            if (dispatcher == null) {
                throw new IllegalStateException("no network dispatcher");
            }
            reflect = new Reflect(loaderClass.getMethod("getInstance").invoke(null), entries,
                    accessible(entryClass.getDeclaredMethod("get")), accessible(entryClass.getDeclaredMethod("isCompleted")),
                    accessible(entryClass.getDeclaredMethod("accept", skinClass)),
                    accessible(entryClass.getDeclaredField("identifier")),
                    loaderClass.getMethod("addSkin", String.class, skinClass),
                    loaderClass.getMethod("loadSkin", String.class),
                    loaderClass.getMethod("loadSkin", String.class, resultHandler),
                    response.getConstructor(String.class, skinClass, Throwable.class),
                    accessible(response.getDeclaredField("identifier")), accessible(response.getDeclaredField("skin")),
                    accessible(response.getDeclaredField("compress")),
                    request, accessible(request.getDeclaredField("identifier")),
                    dispatcher, dispatcherClass.getMethod("split", customPacket, distributor), distributor,
                    proxy.getConstructor(buf), wardrobe.getMethod("of", Entity.class),
                    wardrobePacket.getMethod("sync", wardrobe), wardrobe.getMethod("inventory"),
                    descriptor.getMethod("of", ItemStack.class), descriptor.getMethod("isEmpty"),
                    descriptor.getMethod("identifier"), domain.getMethod("byName", String.class));
            JustReplay.LOGGER.info("Armourer's Workshop detected: skins and wardrobes are recorded and served to replays");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("aw.reflect", "Armourer's Workshop support disabled, its classes changed", t);
        }
        return reflect;
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }

    /**
     * Writes an Armourer's Workshop packet as the clientbound payloads its server would send: the packet id and its
     * data, cut in parts with Armourer's Workshop's begin, body and end markers when it is large. Done here on the
     * calling thread: Armourer's Workshop's own splitter works on a thread of its own and hands the parts back later,
     * after a snapshot is written.
     */
    private static void encode(Reflect r, Object packet, Consumer<Packet<?>> out) throws Exception {
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        buffer.writeInt((int) packetId.invoke(packet));
        packetEncode.invoke(packet, bufWrap.invoke(null, buffer));
        int size = buffer.readableBytes();
        if (size <= PART_SIZE) {
            out.accept(proxyPacket(r, buffer));
            return;
        }
        for (int index = 0; index < size; index += PART_SIZE) {
            int length = Math.min(size - index, PART_SIZE);
            io.netty.buffer.ByteBuf part = io.netty.buffer.Unpooled.buffer(length + 4);
            part.writeInt(index == 0 ? SPLIT_BEGIN : index + PART_SIZE >= size ? SPLIT_END : SPLIT_BODY);
            part.writeBytes(buffer, index, length);
            out.accept(proxyPacket(r, part));
        }
    }

    private static Packet<?> proxyPacket(Reflect r, io.netty.buffer.ByteBuf data) throws Exception {
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.proxyNew.newInstance(bufWrap.invoke(null, data)));
    }

    /** A skin as the answer a server sends for it. */
    private static void writeSkin(Reflect r, String id, Object skin, Consumer<Packet<?>> out) throws Exception {
        Object packet = r.responseNew.newInstance(id, skin, null);
        r.responseCompress.setBoolean(packet, true);
        encode(r, packet, out);
    }

    /** Name of the data domain of a skin id (DATABASE for "db:..."), or "" when unknown. */
    private static String domainOf(Reflect r, String id) {
        try {
            return r.domainByName.invoke(null, id) instanceof Enum<?> e ? e.name() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isServerDomain(Reflect r, String id) {
        return SERVER_DOMAINS.contains(domainOf(r, id));
    }

    // ---------------------------------------------------------------- recording

    /** Ids of the current capture, reset when a capture, a recording or a buffer starts. */
    private static Set<String> recordedIds() {
        Recorder c = Recorder.capturing();
        Object capture = c == null ? null : java.util.Arrays.asList(c, c.recording(), c.buffer());
        if (!java.util.Objects.equals(capture, recordedFor)) {
            synchronized (RECORDED) {
                if (!java.util.Objects.equals(capture, recordedFor)) {
                    RECORDED.clear();
                    REPORTED.clear();
                    lastLoggedCount = -1;
                    recordedFor = capture;
                }
            }
        }
        return RECORDED;
    }

    /** Skin ids an entity wears: its wardrobe and the skinned items it holds or wears. */
    private static void collectWorn(Reflect r, Entity entity, Set<String> ids) {
        try {
            Object wardrobe = r.wardrobeOf.invoke(null, entity);
            if (wardrobe != null && r.wardrobeInventory.invoke(wardrobe) instanceof Container inventory) {
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    collectItem(r, inventory.getItem(i), ids);
                }
            }
            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    collectItem(r, living.getItemBySlot(slot), ids);
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.worn", "Cannot read the Armourer's Workshop skins an entity wears", t);
        }
    }

    private static void collectItem(Reflect r, ItemStack stack, Set<String> ids) throws Exception {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Object descriptor = r.descriptorOf.invoke(null, stack);
        if (descriptor != null && !(boolean) r.descriptorEmpty.invoke(descriptor)
                && r.descriptorId.invoke(descriptor) instanceof String id && !id.isEmpty()) {
            ids.add(id);
        }
    }

    /**
     * Snapshots and checkpoints: every skin the loader holds and every skin a recorded entity wears, as skin answers.
     * In singleplayer a worn skin the loader does not hold is read from the world's database now; on a server it is
     * asked for, and its answer is recorded when it arrives.
     */
    static void writeSnapshot(Consumer<Packet<?>> out) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Set<String> recorded = recordedIds();
        Map<String, Object> skins = new LinkedHashMap<>();
        Map<?, ?> entries = (Map<?, ?>) r.entries.get(r.loader);
        for (Map.Entry<?, ?> e : new ArrayList<>(entries.entrySet())) {
            try {
                Object entry = e.getValue();
                Object skin = (boolean) r.entryCompleted.invoke(entry) ? r.entryGet.invoke(entry) : null;
                if (skin != null) {
                    skins.put((String) e.getKey(), skin);
                }
            } catch (Throwable t) {
                ReplayCompat.warnOnce("aw.entry." + e.getKey(), "Cannot read the Armourer's Workshop skin " + e.getKey(), t);
            }
        }
        Set<String> worn = new LinkedHashSet<>();
        ClientLevel level = mc.level;
        if (level != null) {
            for (Entity entity : level.entitiesForRendering()) {
                collectWorn(r, entity, worn);
            }
        }
        boolean local = mc.hasSingleplayerServer();
        List<String> missing = new ArrayList<>();
        snapshotThread = Thread.currentThread();
        try {
            for (String id : worn) {
                if (skins.containsKey(id)) {
                    continue;
                }
                Object skin = null;
                try {
                    if (local && (isServerDomain(r, id) || "LOCAL".equals(domainOf(r, id)))) {
                        // The integrated server's loader reads the world's database or library in this thread.
                        skin = r.loadSync.invoke(r.loader, id);
                    } else {
                        // Asked without waiting: the skin is recorded when the loader finishes it.
                        r.loadAsync.invoke(r.loader, id, null);
                    }
                } catch (Throwable t) {
                    ReplayCompat.warnOnce("aw.load." + id, "Cannot load the Armourer's Workshop skin " + id, t);
                }
                if (skin != null) {
                    skins.put(id, skin);
                } else if (!recorded.contains(id)) {
                    missing.add(id);
                }
            }
        } finally {
            snapshotThread = null;
        }
        int written = 0;
        for (Map.Entry<String, Object> e : skins.entrySet()) {
            try {
                writeSkin(r, e.getKey(), e.getValue(), out);
                recorded.add(e.getKey());
                written++;
            } catch (Throwable t) {
                ReplayCompat.warnOnce("aw.skin." + e.getKey(), "Cannot record the Armourer's Workshop skin " + e.getKey(), t);
            }
        }
        if (written != lastLoggedCount) {
            lastLoggedCount = written;
            JustReplay.LOGGER.info("Armourer's Workshop: snapshot wrote {} skins ({} worn, {})", written, worn.size(),
                    local ? "singleplayer, read from the world's skin database" : "server, answers recorded as they arrive");
        }
        for (String id : missing) {
            if (REPORTED.add(id)) {
                JustReplay.LOGGER.warn("Armourer's Workshop skin {} is worn but not in the recording{}", id,
                        local ? ": the world's skin database does not have it" : " yet: asked of the server");
            }
        }
    }

    /** The wardrobe an entity wears now, as the server syncs it (snapshots, checkpoints and the actor). */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || entity == null) {
            return;
        }
        try {
            Object wardrobe = r.wardrobeOf.invoke(null, entity);
            if (wardrobe != null) {
                encode(r, r.wardrobeSync.invoke(null, wardrobe), out);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.wardrobe", "Cannot record an Armourer's Workshop wardrobe", t);
        }
    }

    /**
     * The shared skin loader finished a skin (mixin), on any thread. During a capture it is recorded once as a skin
     * answer: in singleplayer and from the client disk cache no answer ever crosses the connection.
     */
    public static void onSkinLoaded(Object entry, Object skin) {
        if (skin == null || Thread.currentThread() == snapshotThread || ReplaySession.current() != null
                || !(Recorder.isRecording() || Recorder.isBuffering())) {
            return;
        }
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        try {
            if (r.entryId.get(entry) instanceof String id && !id.isEmpty() && recordedIds().add(id)) {
                writeSkin(r, id, skin, Recorder::recordSideChannel);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.loaded", "Cannot record a loaded Armourer's Workshop skin", t);
        }
    }

    // ---------------------------------------------------------------- replay

    /**
     * A skin answer handled on the client (mixin). During a replay it fills the skin loader, asked for or not. During
     * a capture it marks the skin as recorded: the connection already carries it.
     */
    public static void onSkinResponse(Object packet) {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        try {
            String id = (String) r.responseId.get(packet);
            Object skin = r.responseSkin.get(packet);
            if (id == null || skin == null) {
                return;
            }
            if (ReplaySession.current() == null) {
                if (Recorder.capturing() != null) {
                    recordedIds().add(id);
                }
                return;
            }
            REPLAY_SKINS.put(id, skin);
            r.addSkin.invoke(r.loader, id, skin);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.response", "Cannot hand a recorded Armourer's Workshop skin to its cache", t);
        }
    }

    /**
     * The skin loader is about to load a skin (mixin): true to skip its own lookup. During a replay a skin the replay
     * holds is served at once; a server skin it does not hold yet waits for its recorded answer, since the replay
     * world's database and host know none of them.
     */
    public static boolean serveReplayLookup(Object entry) {
        if (ReplaySession.current() == null) {
            return false;
        }
        Reflect r = reflect();
        if (r == null) {
            return false;
        }
        try {
            if (!(r.entryId.get(entry) instanceof String id)
                    || ((boolean) r.entryCompleted.invoke(entry) && r.entryGet.invoke(entry) != null)) {
                // Already loaded: the loader returns it without a lookup.
                return false;
            }
            Object skin = REPLAY_SKINS.get(id);
            if (skin != null) {
                r.entryAccept.invoke(entry, skin);
                return true;
            }
            if (isServerDomain(r, id)) {
                if (REPLAY_MISSING.add(id)) {
                    JustReplay.LOGGER.warn("Armourer's Workshop skin {} is worn in the replay but the recording has not "
                            + "played it yet; it shows once its answer plays", id);
                }
                return true;
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.lookup", "Cannot serve an Armourer's Workshop skin lookup in the replay", t);
        }
        return false;
    }

    /**
     * A packet the client sends to its server (mixin): true to drop it. During a replay skin requests have no server
     * that knows the skin; one the replay already played is answered from it.
     */
    public static boolean blockServerbound(Object packet) {
        if (ReplaySession.current() == null) {
            return false;
        }
        Reflect r = reflect();
        if (r == null || !r.requestPacket.isInstance(packet)) {
            return false;
        }
        try {
            String id = (String) r.requestId.get(packet);
            Object skin = id != null ? REPLAY_SKINS.get(id) : null;
            if (skin != null) {
                Minecraft.getInstance().execute(() -> {
                    try {
                        r.addSkin.invoke(r.loader, id, skin);
                    } catch (Throwable t) {
                        ReplayCompat.warnOnce("aw.serve", "Cannot serve an Armourer's Workshop skin to the replay", t);
                    }
                });
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.request", "Cannot read an Armourer's Workshop skin request", t);
        }
        return true;
    }

    /** The replay closed: its skins are released. */
    static void onReplayEnd() {
        REPLAY_SKINS.clear();
        REPLAY_MISSING.clear();
    }
}
