package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Armourer's Workshop skins (outfits, weapons) in recordings and replays.
 * <ul>
 *     <li>Every Armourer's Workshop packet rides one custom payload channel, split in chunks. Wardrobe syncs name the
 *     entity by id, and the recording player's actor shares the recording player's id, so its own wardrobe lands on
 *     the actor as recorded.</li>
 *     <li>A client asks the server for each skin by identifier and keeps the answer in a memory cache. Skins answered
 *     during the recording are recorded as they arrive. Snapshots and checkpoints add every skin the client holds now
 *     (asked before the recording started) and the wardrobe of every entity, which the server sends only on a change
 *     or when it starts tracking the entity.</li>
 *     <li>During a replay a skin answer fills the client cache directly (Armourer's Workshop only hands an answer to a
 *     pending request), and the skins stay held for the whole replay. Skin requests to the replay host, which knows
 *     none of these skins, are dropped, or answered from the skins the replay already played.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Armourer's Workshop class when the mod is absent.
 *
 * @author vyrriox
 */
public final class ArmourersWorkshopCompat {
    private static final String PKG = "moe.plushie.armourers_workshop.";
    /** Skins played in the running replay, held so the mod's soft cache never drops them. */
    private static final Map<String, Object> REPLAY_SKINS = new ConcurrentHashMap<>();
    private static Reflect reflect;
    private static boolean reflectFailed;

    private ArmourersWorkshopCompat() {
    }

    private record Reflect(Object loader, Field entries, Method entryGet, Method entryCompleted, Method addSkin,
                           Constructor<?> responseNew, Field responseId, Field responseSkin, Field responseCompress,
                           Class<?> requestPacket, Field requestId, Object dispatcher, Method split,
                           Class<?> distributor, Constructor<?> proxyNew, Method wardrobeOf, Method wardrobeSync) {
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
            Class<?> customPacket = Class.forName(PKG + "core.network.CustomPacket", false, cl);
            Class<?> response = Class.forName(PKG + "core.network.ResponseSkinPacket", false, cl);
            Class<?> request = Class.forName(PKG + "core.network.RequestSkinPacket", false, cl);
            Class<?> network = Class.forName(PKG + "init.platform.NetworkManager", false, cl);
            Class<?> dispatcherClass = Class.forName(PKG + "init.platform.NetworkManager$Dispatcher", false, cl);
            Class<?> distributor = Class.forName(PKG + "init.platform.NetworkManager$Distributor", false, cl);
            Class<?> buf = Class.forName(PKG + "api.network.IFriendlyByteBuf", false, cl);
            Class<?> proxy = Class.forName(PKG + "compat.forge.AbstractForgeNetwork$Proxy", false, cl);
            Class<?> wardrobe = Class.forName(PKG + "core.capability.SkinWardrobe", false, cl);
            Class<?> wardrobePacket = Class.forName(PKG + "core.network.UpdateWardrobePacket", false, cl);

            Field entries = accessible(loaderClass.getDeclaredField("entries"));
            Field dispatcherField = accessible(network.getDeclaredField("dispatcher"));
            Object dispatcher = dispatcherField.get(null);
            if (dispatcher == null) {
                throw new IllegalStateException("no network dispatcher");
            }
            reflect = new Reflect(loaderClass.getMethod("getInstance").invoke(null), entries,
                    accessible(entryClass.getDeclaredMethod("get")), accessible(entryClass.getDeclaredMethod("isCompleted")),
                    loaderClass.getMethod("addSkin", String.class, skinClass),
                    response.getConstructor(String.class, skinClass, Throwable.class),
                    accessible(response.getDeclaredField("identifier")), accessible(response.getDeclaredField("skin")),
                    accessible(response.getDeclaredField("compress")),
                    request, accessible(request.getDeclaredField("identifier")),
                    dispatcher, dispatcherClass.getMethod("split", customPacket, distributor), distributor,
                    proxy.getConstructor(buf), wardrobe.getMethod("of", Entity.class),
                    wardrobePacket.getMethod("sync", wardrobe));
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

    /** Splits an Armourer's Workshop packet into the clientbound payloads its server would send. */
    private static void encode(Reflect r, Object packet, Consumer<Packet<?>> out) throws Exception {
        List<Object> parts = new ArrayList<>();
        Object collector = java.lang.reflect.Proxy.newProxyInstance(r.distributor.getClassLoader(),
                new Class<?>[]{r.distributor}, (self, method, args) -> switch (method.getName()) {
                    case "add" -> {
                        parts.add(args[1]);
                        yield self;
                    }
                    case "isClientbound" -> true;
                    case "isServerbound" -> false;
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "JustReplaySkinCollector";
                    default -> null;
                });
        r.split.invoke(r.dispatcher, packet, collector);
        for (Object part : parts) {
            out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.proxyNew.newInstance(part)));
        }
    }

    // ---------------------------------------------------------------- recording

    /** Every skin the client holds now, as skin answers (snapshots and checkpoints). */
    static void writeSnapshot(Consumer<Packet<?>> out) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        Map<?, ?> entries = (Map<?, ?>) r.entries.get(r.loader);
        for (Map.Entry<?, ?> e : new ArrayList<>(entries.entrySet())) {
            Object entry = e.getValue();
            try {
                if (!(boolean) r.entryCompleted.invoke(entry)) {
                    continue;
                }
                Object skin = r.entryGet.invoke(entry);
                if (skin == null) {
                    continue;
                }
                Object packet = r.responseNew.newInstance((String) e.getKey(), skin, null);
                r.responseCompress.setBoolean(packet, true);
                encode(r, packet, out);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("aw.skin." + e.getKey(), "Cannot record the Armourer's Workshop skin " + e.getKey(), t);
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

    // ---------------------------------------------------------------- replay

    /** A skin answer handled on the client (mixin): during a replay it fills the skin cache, asked for or not. */
    public static void onSkinResponse(Object packet) {
        if (!ReplaySession.isRunning()) {
            return;
        }
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        try {
            String id = (String) r.responseId.get(packet);
            Object skin = r.responseSkin.get(packet);
            if (id != null && skin != null) {
                REPLAY_SKINS.put(id, skin);
                r.addSkin.invoke(r.loader, id, skin);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aw.response", "Cannot hand a recorded Armourer's Workshop skin to its cache", t);
        }
    }

    /**
     * A packet the client sends to its server (mixin): true to drop it. During a replay skin requests have no server
     * that knows the skin; one the replay already played is answered from it.
     */
    public static boolean blockServerbound(Object packet) {
        if (!ReplaySession.isRunning()) {
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
    }
}
