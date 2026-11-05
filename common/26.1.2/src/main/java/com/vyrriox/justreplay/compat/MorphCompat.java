package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Morph V2 forms in recordings and replays.
 * <ul>
 *     <li>Morph sends a player's form (its morph info: previous and next form, transition time) on one channel,
 *     {@code morph:morph_info}, keyed by the player's entity id. It goes to every player tracking that player and to the
 *     player itself, on each morph and demorph, and when a client asks for it the first time it renders a player. These
 *     packets are recorded and replayed as they are: the recording player's actor uses the recording player's entity
 *     id, so its own form lands on the actor.</li>
 *     <li>A demorphing player turns into a "player form" that names the player by UUID (for its skin). In the
 *     recording player's own forms that UUID is written as the actor's, so the actor keeps its recorded skin.</li>
 *     <li>Snapshots and checkpoints carry the form each player has now: a client asked for it before the recording
 *     started, and the server never sends it again.</li>
 *     <li>A form change handled during a seek starts its transition later, by the ticks the seek skipped.</li>
 *     <li>The other packets of the channel belong to the recording player's own session (morph list, biomass, the
 *     NBT editor screen) and are not replayed. Morph's requests to the replay host are dropped.</li>
 * </ul>
 * The client ticks each player's form entity from the player tick; the optional mixins make it follow the replay clock.
 * Everything is reached by reflection: nothing here loads a Morph class when Morph is absent.
 *
 * @author vyrriox
 */
public final class MorphCompat {
    private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("morph", "morph_info");
    /** Ticks after which the acquisition animation (tendrils taking a mob) has surely ended. */
    private static final int ACQUISITION_TICKS = 200;
    private static Reflect reflect;
    private static boolean reflectFailed;

    private MorphCompat() {
    }

    private record Reflect(Class<?> infoPacket, Class<?> acquisitionPacket, Field entId, Field nbt, Constructor<?> infoNew,
                           Constructor<?> holderNew, Method inner, Object handler, Method getMorphInfo, Method isMorphed,
                           Method write) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.MORPH) {
            return reflect;
        }
        try {
            ClassLoader loader = MorphCompat.class.getClassLoader();
            Class<?> abstractPacket = Class.forName("me.ichun.mods.ichunutil.common.network.AbstractPacket", false, loader);
            Class<?> holder = Class.forName("me.ichun.mods.ichunutil.common.network.PacketChannel$PacketHolderPayload", false, loader);
            Class<?> infoPacket = Class.forName("me.ichun.mods.morph.common.packet.PacketMorphInfo", false, loader);
            Class<?> acquisitionPacket = Class.forName("me.ichun.mods.morph.common.packet.PacketAcquisition", false, loader);
            Class<?> handlerClass = Class.forName("me.ichun.mods.morph.common.morph.MorphHandler", false, loader);
            Class<?> morphInfo = Class.forName("me.ichun.mods.morph.api.morph.MorphInfo", false, loader);
            Constructor<?> holderNew = holder.getDeclaredConstructor(Identifier.class, abstractPacket);
            Method inner = holder.getDeclaredMethod("inner");
            holderNew.setAccessible(true);
            inner.setAccessible(true);
            reflect = new Reflect(infoPacket, acquisitionPacket, infoPacket.getField("entId"), infoPacket.getField("nbt"),
                    infoPacket.getConstructor(int.class, CompoundTag.class), holderNew, inner,
                    handlerClass.getField("INSTANCE").get(null), handlerClass.getMethod("getMorphInfo", Player.class),
                    morphInfo.getMethod("isMorphed"), morphInfo.getMethod("write", CompoundTag.class));
            JustReplay.LOGGER.info("Morph detected: player forms are recorded and replayed, the local player's for its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("morph.reflect", "Morph support disabled, its classes changed", t);
        }
        return reflect;
    }

    /** The inner Morph packet of a clientbound packet on Morph's channel, or null. */
    private static Object innerOf(Reflect r, Packet<?> packet) throws Exception {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !CHANNEL.equals(custom.payload().type().id())) {
            return null;
        }
        return r.inner.invoke(custom.payload());
    }

    private static Packet<?> infoPacket(Reflect r, int entityId, CompoundTag nbt) throws Exception {
        Object inner = r.infoNew.newInstance(entityId, nbt);
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.holderNew.newInstance(CHANNEL, inner));
    }

    // ---------------------------------------------------------------- recording

    /**
     * See {@link ReplayCompat#rewriteRecorded}: in a form packet, the recording player's UUID (its player form) becomes
     * the actor's. Other packets are kept as they are.
     */
    static Packet<?> rewriteRecorded(Packet<?> packet, UUID localUuid, UUID actorUuid) throws Exception {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !CHANNEL.equals(custom.payload().type().id())) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        Object inner = r.inner.invoke(custom.payload());
        if (!r.infoPacket.isInstance(inner)) {
            return packet;
        }
        CompoundTag nbt = (CompoundTag) r.nbt.get(inner);
        if (nbt == null || localUuid.equals(actorUuid)) {
            return packet;
        }
        CompoundTag copy = nbt.copy();
        return replaceUuid(copy, localUuid, actorUuid) ? infoPacket(r, r.entId.getInt(inner), copy) : packet;
    }

    /**
     * Writes the form a player has now (snapshots and checkpoints), as the server sends it, for the player with this
     * UUID in the replay. The actor shares the recording player's entity id; only the UUID in its player form changes.
     */
    static void writeCurrent(Player player, UUID target, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || player == null) {
            return;
        }
        try {
            Object info = r.getMorphInfo.invoke(r.handler, player);
            if (info == null || !(boolean) r.isMorphed.invoke(info)) {
                return;
            }
            CompoundTag nbt = (CompoundTag) r.write.invoke(info, new CompoundTag());
            if (!player.getUUID().equals(target)) {
                replaceUuid(nbt, player.getUUID(), target);
            }
            out.accept(infoPacket(r, player.getId(), nbt));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("morph.snapshot", "Cannot record the Morph form of " + player.getName().getString(), t);
        }
    }

    /** Replaces a UUID (stored as four ints) anywhere in a tag. True when one was found. */
    private static boolean replaceUuid(Tag tag, UUID from, UUID to) {
        return replaceUuid(tag, UUIDUtil.uuidToIntArray(from), new net.minecraft.nbt.IntArrayTag(UUIDUtil.uuidToIntArray(to)));
    }

    private static boolean replaceUuid(Tag tag, int[] from, IntArrayTag to) {
        boolean found = false;
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet().toArray(new String[0])) {
                Tag child = compound.get(key);
                if (child instanceof IntArrayTag ints && Arrays.equals(ints.getAsIntArray(), from)) {
                    compound.put(key, to.copy());
                    found = true;
                } else if (child != null) {
                    found |= replaceUuid(child, from, to);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag child = list.get(i);
                if (child instanceof IntArrayTag ints && Arrays.equals(ints.getAsIntArray(), from)) {
                    list.set(i, to.copy());
                    found = true;
                } else {
                    found |= replaceUuid(child, from, to);
                }
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- replay

    /**
     * See {@link ReplayCompat#consumeRecorded}: only form changes and the acquisition animation are replayed. The rest
     * of the channel is the recording player's own session (morph list, biomass, a screen to open).
     */
    static boolean consumeRecorded(Packet<?> packet) {
        Reflect r = reflect();
        if (r == null) {
            return false;
        }
        try {
            Object inner = innerOf(r, packet);
            return inner != null && !r.infoPacket.isInstance(inner) && !r.acquisitionPacket.isInstance(inner);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("morph.consume", "Cannot read a recorded Morph packet", t);
            return false;
        }
    }

    /** Morph's requests to the replay host (form info, morph selection, abilities) have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && CHANNEL.equals(custom.payload().type().id());
    }

    /**
     * See {@link ReplayCompat#adjustSkipped}: a form change handled during a seek is moved {@code late} ticks into its
     * transition. A transition that has ended by then stops one tick before its end, so Morph ends it on the next tick.
     * An acquisition animation long over at the target is dropped.
     */
    static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        try {
            Object inner = innerOf(r, packet);
            if (r.acquisitionPacket.isInstance(inner)) {
                // The acquisition animation would play in full after the seek: dropped once it is surely over.
                return late > ACQUISITION_TICKS ? null : packet;
            }
            if (!r.infoPacket.isInstance(inner)) {
                return packet;
            }
            CompoundTag nbt = (CompoundTag) r.nbt.get(inner);
            if (nbt == null || !nbt.contains("prevState") || !nbt.contains("nextState")) {
                return packet;
            }
            int morphingTime = nbt.getIntOr("morphingTime", 0);
            int morphTime = nbt.getIntOr("morphTime", 0);
            if (morphingTime <= 0 || morphTime >= morphingTime - 1) {
                return packet;
            }
            CompoundTag copy = nbt.copy();
            copy.putInt("morphTime", (int) Math.min((long) morphTime + late, morphingTime - 1L));
            return infoPacket(r, r.entId.getInt(inner), copy);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("morph.seek", "Cannot move a Morph transition to the seek target, it starts from its beginning", t);
            return packet;
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** True when an entity is a player in a Morph form; false without Morph. */
    public static boolean isMorphed(Entity entity) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof Player player)) {
            return false;
        }
        try {
            Object info = r.getMorphInfo.invoke(r.handler, player);
            return info != null && (boolean) r.isMorphed.invoke(info);
        } catch (Throwable t) {
            return false;
        }
    }
}
