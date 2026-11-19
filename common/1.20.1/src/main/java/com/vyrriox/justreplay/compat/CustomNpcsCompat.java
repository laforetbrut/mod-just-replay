package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.Platform;
import com.vyrriox.justreplay.record.Payloads;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Custom NPCs (unofficial 1.20.1 port) in recordings and replays.
 * <ul>
 *     <li>Custom payloads are raw bytes in 1.20.1. On Forge (and NeoForge 1.20.1, which runs the Forge build) every
 *     Custom NPCs packet rides one channel with its message index as the first byte; on Fabric each packet has a
 *     channel of its own named by that index. The indexes follow the mod's registration order, which changes between
 *     builds, so they are read from the running mod.</li>
 *     <li>An NPC's look (model, skin, size, name, held items, animations) is its spawn data. On Forge it travels in
 *     Forge's spawn message, or, with Custom NPCs' invisible NPCs on (its default), in its own packets after a vanilla
 *     spawn. On Fabric it is appended to the vanilla add entity packet, which the client forgets when it encodes the
 *     packet again: the encoded bytes get it back. Snapshots and checkpoints also write each NPC's current look,
 *     read from the client entity, as the update packet the server sends when an NPC changes.</li>
 *     <li>Player skins set by Custom NPCs are sent once on join; snapshots carry the client's current ones.</li>
 *     <li>Packets that open screens, dialogs, overlays or toasts for the recording player are not replayed, and the
 *     client's Custom NPCs requests to the replay host are dropped.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads a Custom NPCs class when the mod is absent.
 *
 * @author vyrriox
 */
public final class CustomNpcsCompat {
    private static final String NAMESPACE = "customnpcs";
    /** The Forge channel of every Custom NPCs packet. */
    private static final ResourceLocation FORGE_CHANNEL = new ResourceLocation(NAMESPACE, "packets");
    private static final String CLIENT_PACKAGE = "noppes.npcs.packets.client.";
    /** Clientbound packets that belong to the recording player's own session (screens, overlays, toasts). */
    private static final Set<String> SESSION_ONLY = Set.of("PacketDialog", "PacketDialogDummy", "PacketGuiCloneOpen",
            "PacketGuiClose", "PacketGuiComponentUpdate", "PacketGuiData", "PacketGuiError", "PacketGuiOpen", "PacketGuiParts",
            "PacketGuiScrollData", "PacketGuiScrollList", "PacketGuiScrollSelected", "PacketGuiUpdate", "PacketHideAllOverlays",
            "PacketOverlayHide", "PacketOverlayShow", "PacketAchievement", "PacketQuestCompletion", "PacketSoundGUIOpen",
            "PacketNpcEdit", "PacketNpcRole", "PacketItemUpdate");

    private static Reflect reflect;
    private static boolean reflectFailed;

    private CustomNpcsCompat() {
    }

    /**
     * {@code channel} and {@code encodeMessage} on Forge, {@code encoders} on Fabric; {@code indexes} maps each packet
     * class to its index. The add entity packet accessors exist on Fabric only.
     */
    private record Reflect(Class<?> npc, Method writeSpawnTag, Map<Class<?>, Integer> indexes, Set<Integer> sessionOnly,
                           Object channel, Method encodeMessage, Map<?, ?> encoders, Field skinInfo,
                           Constructor<?> syncSkinNew, Constructor<?> npcUpdateNew, Class<?> addEntityMixin,
                           Method getEntity, Method getBuf, Method writeSpawnData) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.CUSTOM_NPCS) {
            return reflect;
        }
        try {
            ClassLoader loader = CustomNpcsCompat.class.getClassLoader();
            Class<?> npc = Class.forName("noppes.npcs.entity.EntityNPCInterface", false, loader);
            Class<?> skinData = Class.forName("noppes.npcs.controllers.data.PlayerSkinData", false, loader);
            Class<?> skins = Class.forName("noppes.npcs.client.controllers.ClientSkinController", false, loader);
            Class<?> syncSkin = Class.forName(CLIENT_PACKAGE + "PacketSyncSkin", false, loader);
            Class<?> npcUpdate = Class.forName(CLIENT_PACKAGE + "PacketNpcUpdate", false, loader);
            Class<?> packets = Class.forName("noppes.npcs.packets.Packets", false, loader);
            Field skinInfo = skins.getDeclaredField("skinInfo");
            skinInfo.setAccessible(true);
            Map<Class<?>, Integer> indexes = new HashMap<>();
            Object channel = null;
            Method encodeMessage = null;
            Map<?, ?> encoders = null;
            Class<?> mixin = null;
            Method getEntity = null;
            Method getBuf = null;
            Method writeSpawnData = null;
            if ("fabric".equals(Platform.get().loaderName())) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) packets.getField("indexes").get(null)).entrySet()) {
                    indexes.put((Class<?>) e.getKey(), (Integer) e.getValue());
                }
                encoders = (Map<?, ?>) packets.getField("encoders").get(null);
                mixin = Class.forName("noppes.npcs.mixinintf.IMixinClientboundAddEntityPacket", false, loader);
                getEntity = mixin.getMethod("getEntity");
                getBuf = mixin.getMethod("getBuf");
                writeSpawnData = npc.getMethod("writeSpawnData", FriendlyByteBuf.class);
            } else {
                channel = packets.getField("Channel").get(null);
                if (channel == null) {
                    throw new IllegalStateException("no network channel");
                }
                encodeMessage = channel.getClass().getMethod("encodeMessage", Object.class, FriendlyByteBuf.class);
                // Forge's message codec holds the index of every message class it was given.
                Field codecField = channel.getClass().getDeclaredField("indexedCodec");
                codecField.setAccessible(true);
                Object codec = codecField.get(channel);
                Field typesField = codec.getClass().getDeclaredField("types");
                typesField.setAccessible(true);
                for (Map.Entry<?, ?> e : ((Map<?, ?>) typesField.get(codec)).entrySet()) {
                    Field index = e.getValue().getClass().getDeclaredField("index");
                    index.setAccessible(true);
                    indexes.put((Class<?>) e.getKey(), index.getInt(e.getValue()));
                }
            }
            Set<Integer> sessionOnly = new java.util.HashSet<>();
            for (Map.Entry<Class<?>, Integer> e : indexes.entrySet()) {
                String name = e.getKey().getName();
                if (name.startsWith(CLIENT_PACKAGE) && SESSION_ONLY.contains(name.substring(CLIENT_PACKAGE.length()))) {
                    sessionOnly.add(e.getValue());
                }
            }
            if (!indexes.containsKey(syncSkin) || !indexes.containsKey(npcUpdate)) {
                throw new IllegalStateException("Custom NPCs registered " + indexes.size() + " packets, not its skin and NPC updates");
            }
            reflect = new Reflect(npc, npc.getMethod("writeSpawnData"), Map.copyOf(indexes), Set.copyOf(sessionOnly), channel,
                    encodeMessage, encoders, skinInfo, syncSkin.getConstructor(String.class, skinData),
                    npcUpdate.getConstructor(int.class, net.minecraft.nbt.CompoundTag.class), mixin, getEntity, getBuf,
                    writeSpawnData);
        } catch (Throwable t) {
            reflectFailed = true;
            JustReplay.LOGGER.warn("Custom NPCs found but its classes are not as expected; NPC looks may be missing from replays", t);
        }
        return reflect;
    }

    /** A Custom NPCs packet as the server sends it to a client: on its Forge channel, or on its own Fabric channel. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Packet<?> payload(Reflect r, Object message) throws ReflectiveOperationException {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        if (r.channel() != null) {
            r.encodeMessage().invoke(r.channel(), message, buf);
            return Payloads.make(FORGE_CHANNEL, buf);
        }
        Integer index = r.indexes().get(message.getClass());
        BiConsumer encoder = (BiConsumer) r.encoders().get(message.getClass());
        if (index == null || encoder == null) {
            throw new IllegalStateException("no Custom NPCs channel for " + message.getClass().getSimpleName());
        }
        encoder.accept(message, buf);
        return Payloads.make(new ResourceLocation(NAMESPACE, Integer.toString(index)), buf);
    }

    /** Index of a clientbound Custom NPCs packet, -1 for any other packet. */
    private static int indexOf(Reflect r, Packet<?> packet) {
        ResourceLocation id = Payloads.idOf(packet);
        if (id == null || !NAMESPACE.equals(id.getNamespace())) {
            return -1;
        }
        if (r.channel() == null) {
            try {
                return Integer.parseInt(id.getPath());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (!FORGE_CHANNEL.equals(id)) {
            return -1;
        }
        FriendlyByteBuf data = Payloads.bytes(packet);
        if (data == null) {
            return -1;
        }
        try {
            return data.isReadable() ? data.getUnsignedByte(data.readerIndex()) : -1;
        } finally {
            data.release();
        }
    }

    /**
     * Fabric: the encoded bytes of a packet with Custom NPCs' spawn data restored, the NPC's current look when the
     * client has it (snapshots), else the bytes the packet was decoded with. Other packets, and every packet on Forge
     * (where the look never rides the vanilla packet), are returned unchanged.
     */
    static byte[] patchEncoded(Packet<?> packet, byte[] bytes) {
        if (!(packet instanceof ClientboundAddEntityPacket add)) {
            return bytes;
        }
        Reflect r = reflect();
        if (r == null || r.addEntityMixin() == null || !r.addEntityMixin().isInstance(packet) || bytes.length == 0
                || bytes[bytes.length - 1] != 0) {
            return bytes;
        }
        try {
            if (r.npc().isInstance(r.getEntity().invoke(packet))) {
                // Built from a live entity (integrated server): Custom NPCs wrote the data itself.
                return bytes;
            }
            byte[] data = currentLook(r, add.getId());
            if (data == null && r.getBuf().invoke(packet) instanceof FriendlyByteBuf buf && buf.writerIndex() > 0) {
                data = new byte[buf.writerIndex()];
                buf.getBytes(0, data);
            }
            if (data == null) {
                return bytes;
            }
            FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(bytes.length + data.length + 5));
            try {
                out.writeBytes(bytes, 0, bytes.length - 1);
                out.writeVarInt(data.length);
                out.writeBytes(data);
                byte[] patched = new byte[out.readableBytes()];
                out.readBytes(patched);
                return patched;
            } finally {
                out.release();
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("customnpcs.spawn", "Cannot record the look of a Custom NPCs entity", t);
            return bytes;
        }
    }

    /** The spawn data of the client's NPC with this id, read on the client thread only (snapshots); null otherwise. */
    private static byte[] currentLook(Reflect r, int id) throws ReflectiveOperationException {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || !mc.isSameThread()) {
            return null;
        }
        Entity entity = mc.level.getEntity(id);
        if (!r.npc().isInstance(entity)) {
            return null;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            r.writeSpawnData().invoke(entity, buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    /**
     * The look an NPC has now (snapshots and checkpoints, after its spawn), as the update the server sends when the NPC
     * changes: the spawn packets of a snapshot may carry none (Forge's vanilla spawn of invisible NPCs).
     */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || !r.npc().isInstance(entity)) {
            return;
        }
        try {
            Object look = r.writeSpawnTag().invoke(entity);
            if (look != null) {
                out.accept(payload(r, r.npcUpdateNew().newInstance(entity.getId(), look)));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("customnpcs.look", "Cannot record the current look of a Custom NPCs entity", t);
        }
    }

    /** Player skins set by Custom NPCs, sent by the server only on join. */
    static void writeSnapshot(Consumer<Packet<?>> out) throws ReflectiveOperationException {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        Map<?, ?> skins = (Map<?, ?>) r.skinInfo().get(null);
        if (skins == null) {
            return;
        }
        for (Map.Entry<?, ?> entry : Map.copyOf(skins).entrySet()) {
            if (entry.getKey() instanceof String name && entry.getValue() != null) {
                out.accept(payload(r, r.syncSkinNew().newInstance(name, entry.getValue())));
            }
        }
    }

    /** Drops packets that would open a screen, an overlay or a toast during the replay. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (!Payloads.isClientbound(packet)) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        int index = indexOf(r, packet);
        return index >= 0 && r.sessionOnly().contains(index) ? null : packet;
    }

    /** Custom NPCs requests have no server to answer them in a replay. */
    static boolean blockServerbound(Packet<?> packet) {
        ResourceLocation id = Payloads.isServerbound(packet) ? Payloads.idOf(packet) : null;
        return id != null && NAMESPACE.equals(id.getNamespace());
    }
}
