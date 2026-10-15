package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Emotecraft emotes in recordings and replays.
 * <ul>
 *     <li>Emotes of other players reach the client as {@code emotecraft:emote} packets and are recorded as they are.
 *     The server never sends a player its own emotes, so the recorder polls the local player and writes the packets
 *     another player would have received, aimed at the recording player's actor.</li>
 *     <li>Checkpoints and mid-session snapshots carry the emote each player is playing, at its current tick.</li>
 *     <li>A seek handles the skipped emote packets late: their start tick is moved forward by the time skipped, and an
 *     emote already over at the seek target is dropped.</li>
 *     <li>Editor actors can play an emote by name (the EMOTE action).</li>
 * </ul>
 * Pause and speed are handled by the player animation library mixins (see {@code PlayerAnimStackMixin}). Everything is
 * reached by reflection: nothing here loads an Emotecraft class.
 *
 * @author vyrriox
 */
public final class EmotecraftCompat {
    private static final ResourceLocation EMOTE_CHANNEL = ResourceLocation.fromNamespaceAndPath("emotecraft", "emote");
    private static Reflect reflect;
    private static boolean reflectFailed;
    /** Actors playing an emote started by the EMOTE action. */
    private static final Map<Entity, Object> ACTOR_EMOTES = new WeakHashMap<>();

    private EmotecraftCompat() {
    }

    private record Reflect(Class<?> playerEntity, Method isPlayingEmote, Method getEmote, Method playEmote, Method stopEmote,
                           Method emoteData, Method emoteTick, Method animUuid, Method animName, Method isPlayingAt,
                           Field isInfinite, Class<?> builder, Method streamEmote, Method emoteTickSetter, Method sendStop,
                           Method build, Method write, Method read, Field netPurpose, Field netTick, Field netEmote,
                           Object streamTask, Method playPacket, Method payloadBytes, Method emoteList, Method fromJson) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.EMOTECRAFT) {
            return reflect;
        }
        try {
            ClassLoader loader = EmotecraftCompat.class.getClassLoader();
            Class<?> playerEntity = Class.forName("io.github.kosmx.emotes.executor.emotePlayer.IEmotePlayerEntity", false, loader);
            Class<?> emotePlayer = Class.forName("io.github.kosmx.emotes.executor.emotePlayer.IEmotePlayer", false, loader);
            Class<?> anim = Class.forName("dev.kosmx.playerAnim.core.data.KeyframeAnimation", false, loader);
            Class<?> builder = Class.forName("io.github.kosmx.emotes.common.network.EmotePacket$Builder", false, loader);
            Class<?> packet = Class.forName("io.github.kosmx.emotes.common.network.EmotePacket", false, loader);
            Class<?> netData = Class.forName("io.github.kosmx.emotes.common.network.objects.NetData", false, loader);
            Class<?> task = Class.forName("io.github.kosmx.emotes.common.network.PacketTask", false, loader);
            Class<?> tools = Class.forName("io.github.kosmx.emotes.arch.network.NetworkPlatformTools", false, loader);
            Class<?> payload = Class.forName("io.github.kosmx.emotes.arch.network.EmotePacketPayload", false, loader);
            Class<?> api = Class.forName("io.github.kosmx.emotes.api.events.client.ClientEmoteAPI", false, loader);
            Class<?> platform = Class.forName("io.github.kosmx.emotes.PlatformTools", false, loader);
            Object stream = null;
            for (Object constant : task.getEnumConstants()) {
                if ("STREAM".equals(((Enum<?>) constant).name())) {
                    stream = constant;
                }
            }
            reflect = new Reflect(playerEntity, playerEntity.getMethod("isPlayingEmote"), playerEntity.getMethod("emotecraft$getEmote"),
                    playerEntity.getMethod("emotecraft$playEmote", anim, int.class, boolean.class), playerEntity.getMethod("stopEmote"),
                    emotePlayer.getMethod("getData"), emotePlayer.getMethod("getTick"), anim.getMethod("getUuid"),
                    anim.getMethod("getName"), anim.getMethod("isPlayingAt", int.class), anim.getField("isInfinite"), builder,
                    builder.getMethod("configureToStreamEmote", anim, UUID.class), builder.getMethod("configureEmoteTick", int.class),
                    builder.getMethod("configureToSendStop", UUID.class, UUID.class), builder.getMethod("build"),
                    packet.getMethod("write"), packet.getMethod("read", ByteBuffer.class), netData.getField("purpose"),
                    netData.getField("tick"), netData.getField("emoteData"), stream, tools.getMethod("playPacket", ByteBuffer.class),
                    payload.getMethod("bytes"), api.getMethod("clientEmoteList"), platform.getMethod("fromJson", String.class));
            JustReplay.LOGGER.info("Emotecraft detected: emotes are recorded for the local player and follow the replay clock");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("emotecraft.reflect", "Emotecraft support disabled, its classes changed", t);
        }
        return reflect;
    }

    // ---------------------------------------------------------------- packets

    /** The packet another client receives when a player starts an emote at a tick. */
    private static Packet<?> playPacket(Reflect r, Object anim, UUID player, int tick) throws Exception {
        Object builder = r.builder.getConstructor().newInstance();
        r.streamEmote.invoke(builder, anim, player);
        r.emoteTickSetter.invoke(builder, tick);
        ByteBuffer bytes = (ByteBuffer) r.write.invoke(r.build.invoke(builder));
        return (Packet<?>) r.playPacket.invoke(null, bytes);
    }

    /** The packet another client receives when a player stops an emote. */
    private static Packet<?> stopPacket(Reflect r, Object anim, UUID player) throws Exception {
        Object builder = r.builder.getConstructor().newInstance();
        r.sendStop.invoke(builder, r.animUuid.invoke(anim), player);
        ByteBuffer bytes = (ByteBuffer) r.write.invoke(r.build.invoke(builder));
        return (Packet<?>) r.playPacket.invoke(null, bytes);
    }

    /** The emote animation an entity is playing, or null. */
    private static Object playing(Reflect r, Object entity) throws Exception {
        if (!r.playerEntity.isInstance(entity) || !(boolean) r.isPlayingEmote.invoke(entity)) {
            return null;
        }
        return r.getEmote.invoke(entity);
    }

    /** Writes the emote a player is playing now (snapshots and checkpoints), as received by another client. */
    public static void writeCurrent(Entity player, UUID target, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        try {
            Object emote = playing(r, player);
            if (emote != null) {
                out.accept(playPacket(r, r.emoteData.invoke(emote), target, (int) r.emoteTick.invoke(emote)));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("emotecraft.snapshot", "Cannot record the emote of " + player.getName().getString(), t);
        }
    }

    /**
     * Emote state of the local player, polled every recorded tick: emotes it starts or stops are written as the
     * packets another client would receive.
     */
    public static final class LocalEmotes {
        private Object lastEmote;
        private Object lastAnim;

        /**
         * @param respawned the actor was spawned again this tick: the emote in progress is written again
         */
        public void poll(Entity player, UUID actor, boolean respawned, Consumer<Packet<?>> out) {
            Reflect r = reflect();
            if (r == null) {
                return;
            }
            try {
                Object emote = playing(r, player);
                Object anim = emote == null ? null : r.emoteData.invoke(emote);
                if (anim != null && (respawned || emote != lastEmote || anim != lastAnim)) {
                    out.accept(playPacket(r, anim, actor, (int) r.emoteTick.invoke(emote)));
                } else if (anim == null && lastAnim != null) {
                    out.accept(stopPacket(r, lastAnim, actor));
                }
                lastEmote = emote;
                lastAnim = anim;
            } catch (Throwable t) {
                ReplayCompat.warnOnce("emotecraft.local", "Cannot record the local player's emotes", t);
            }
        }
    }

    /** See {@link ReplayCompat#adjustSkipped}: emote packets handled during a seek start later in the emote. */
    static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        if (!EMOTE_CHANNEL.equals(payload.type().id())) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        try {
            ByteBuffer bytes = ((ByteBuffer) r.payloadBytes.invoke(payload)).duplicate();
            Object data = r.read.invoke(r.build.invoke(r.builder.getConstructor().newInstance()), bytes);
            if (data == null || r.netPurpose.get(data) != r.streamTask) {
                return packet;
            }
            Object anim = r.netEmote.get(data);
            if (anim == null) {
                return packet;
            }
            int tick = r.netTick.getInt(data) + late;
            if (!r.isInfinite.getBoolean(anim) && !(boolean) r.isPlayingAt.invoke(anim, tick)) {
                // Over before the seek target: playing it would show a finished emote from its start.
                return null;
            }
            r.netTick.setInt(data, tick);
            Object builder = r.builder.getConstructor(data.getClass()).newInstance(data);
            return (Packet<?>) r.playPacket.invoke(null, (ByteBuffer) r.write.invoke(r.build.invoke(builder)));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("emotecraft.seek", "Cannot move an emote to the seek target, it starts from its beginning", t);
            return packet;
        }
    }

    /** Starts an emote of the local list on the local player, as the emote wheel does. False when it cannot. */
    public static boolean playLocal(String name) {
        Reflect r = reflect();
        if (r == null) {
            return false;
        }
        try {
            Object anim = findEmote(r, name);
            if (anim == null) {
                return false;
            }
            Class<?> api = Class.forName("io.github.kosmx.emotes.api.events.client.ClientEmoteAPI", false,
                    EmotecraftCompat.class.getClassLoader());
            return (boolean) api.getMethod("playEmote", anim.getClass()).invoke(null, anim);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("emotecraft.play", "Cannot play a local emote", t);
            return false;
        }
    }

    /** The emote an entity plays and its tick ("name@tick"), "none", or "n/a" without Emotecraft. */
    public static String describe(Entity entity) {
        Reflect r = reflect();
        if (r == null || entity == null) {
            return "n/a";
        }
        try {
            Object emote = playing(r, entity);
            if (emote == null) {
                return "none";
            }
            return safeName(r, r.emoteData.invoke(emote)) + "@" + r.emoteTick.invoke(emote);
        } catch (Throwable t) {
            return "error " + (t.getCause() != null ? t.getCause() : t);
        }
    }

    /**
     * The name of an emote. Emotes received from the network may keep their name as a JSON element, which makes
     * {@code getName} throw: the UUID is used then.
     */
    private static String safeName(Reflect r, Object anim) {
        try {
            return String.valueOf(r.animName.invoke(anim));
        } catch (Throwable t) {
            try {
                return String.valueOf(r.animUuid.invoke(anim));
            } catch (Throwable ignored) {
                return "?";
            }
        }
    }

    // ---------------------------------------------------------------- editor actors

    /** The emote of the local emote list with this name (or UUID), or null. */
    private static Object findEmote(Reflect r, String name) throws Exception {
        String wanted = normalize(name);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Object anim : (Collection<?>) r.emoteList.invoke(null)) {
            if (name.trim().equalsIgnoreCase(String.valueOf(r.animUuid.invoke(anim)))) {
                return anim;
            }
            String raw;
            try {
                raw = (String) r.animName.invoke(anim);
            } catch (Throwable t) {
                continue;
            }
            if (raw == null) {
                continue;
            }
            if (wanted.equals(normalize(raw))) {
                return anim;
            }
            try {
                Object text = r.fromJson.invoke(null, raw);
                if (text instanceof net.minecraft.network.chat.Component component && wanted.equals(normalize(component.getString()))) {
                    return anim;
                }
            } catch (Throwable ignored) {
                // Plain names are not JSON.
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * Makes an actor play the named emote at a tick of the emote; restarts it when it drifted (after a seek). Does
     * nothing when Emotecraft is missing, the actor is not a player or no emote has that name.
     */
    public static void applyActorEmote(Entity actor, String name, int tick) {
        Reflect r = reflect();
        if (r == null || !r.playerEntity.isInstance(actor)) {
            return;
        }
        try {
            Object anim = findEmote(r, name);
            if (anim == null) {
                ReplayCompat.warnOnce("emotecraft.unknown." + name, "No emote named " + name, null);
                return;
            }
            if (!r.isInfinite.getBoolean(anim) && !(boolean) r.isPlayingAt.invoke(anim, tick)) {
                stopActorEmote(actor);
                return;
            }
            Object emote = playing(r, actor);
            Object current = emote == null ? null : r.emoteData.invoke(emote);
            int currentTick = emote == null ? Integer.MIN_VALUE : (int) r.emoteTick.invoke(emote);
            if (current != anim || Math.abs(currentTick - tick) > 2) {
                r.playEmote.invoke(actor, anim, tick, false);
                ACTOR_EMOTES.put(actor, anim);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("emotecraft.actor", "Cannot play an emote on an actor", t);
        }
    }

    /** Stops the emote an EMOTE action started on an actor. */
    public static void stopActorEmote(Entity actor) {
        if (ACTOR_EMOTES.remove(actor) == null) {
            return;
        }
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        try {
            r.stopEmote.invoke(actor);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("emotecraft.actor.stop", "Cannot stop an actor emote", t);
        }
    }
}
