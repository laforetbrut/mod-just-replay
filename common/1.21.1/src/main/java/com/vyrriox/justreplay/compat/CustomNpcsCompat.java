package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Custom NPCs (unofficial NeoForge port) in recordings and replays.
 * <ul>
 *     <li>An NPC's look (model, skin, size, name, held items, animations) travels as NBT appended by Custom NPCs to
 *     the vanilla add entity packet. The packet decoded on the client keeps those bytes but forgets them when it is
 *     encoded again, so the recorded packet would carry no look. The encoded bytes get them back. In a snapshot or
 *     checkpoint the NPC's current look is written instead, read from the client entity, so NPCs present before the
 *     recording and NPCs changed since their spawn appear as they are now.</li>
 *     <li>Later look changes come in Custom NPCs' own update packet, recorded and replayed as they are.</li>
 *     <li>Player skins set by Custom NPCs are sent once on join; snapshots carry the client's current ones.</li>
 *     <li>Packets that open screens, dialogs, overlays or toasts for the recording player are not replayed, and the
 *     client's Custom NPCs requests to the replay host are dropped.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads a Custom NPCs class when the mod is absent.
 *
 * @author vyrriox
 */
public final class CustomNpcsCompat {
    private static final String CLIENT_PACKAGE = "noppes.npcs.packets.client.";
    private static final String SERVER_PACKAGE = "noppes.npcs.packets.server.";
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

    private record Reflect(Class<?> npc, Class<?> addEntityMixin, Method getEntity, Method getBuf, Method writeSpawnData,
                           Field skinInfo, Constructor<?> syncSkinNew) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.CUSTOM_NPCS) {
            return reflect;
        }
        try {
            ClassLoader loader = CustomNpcsCompat.class.getClassLoader();
            Class<?> npc = Class.forName("noppes.npcs.entity.EntityNPCInterface", false, loader);
            Class<?> mixin = Class.forName("noppes.npcs.mixinintf.IMixinClientboundAddEntityPacket", false, loader);
            Class<?> skinData = Class.forName("noppes.npcs.controllers.data.PlayerSkinData", false, loader);
            Class<?> skins = Class.forName("noppes.npcs.client.controllers.ClientSkinController", false, loader);
            Class<?> syncSkin = Class.forName(CLIENT_PACKAGE + "PacketSyncSkin", false, loader);
            Field skinInfo = skins.getDeclaredField("skinInfo");
            skinInfo.setAccessible(true);
            reflect = new Reflect(npc, mixin, mixin.getMethod("getEntity"), mixin.getMethod("getBuf"),
                    npc.getMethod("writeSpawnData", FriendlyByteBuf.class), skinInfo,
                    syncSkin.getConstructor(String.class, skinData));
        } catch (Throwable t) {
            reflectFailed = true;
            JustReplay.LOGGER.warn("Custom NPCs found but its classes are not as expected; NPC looks may be missing from replays", t);
        }
        return reflect;
    }

    /**
     * The encoded bytes of a packet with Custom NPCs' spawn data restored: the NPC's current look when the client has
     * it (snapshots), else the bytes the packet was decoded with. Other packets are returned unchanged.
     */
    static byte[] patchEncoded(Packet<?> packet, byte[] bytes) {
        if (!(packet instanceof ClientboundAddEntityPacket add)) {
            return bytes;
        }
        Reflect r = reflect();
        if (r == null || !r.addEntityMixin().isInstance(packet) || bytes.length == 0 || bytes[bytes.length - 1] != 0) {
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
                out.accept(new ClientboundCustomPayloadPacket(
                        (CustomPacketPayload) r.syncSkinNew().newInstance(name, entry.getValue())));
            }
        }
    }

    /** Drops packets that would open a screen, an overlay or a toast during the replay. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            String name = custom.payload().getClass().getName();
            if (name.startsWith(CLIENT_PACKAGE) && SESSION_ONLY.contains(name.substring(CLIENT_PACKAGE.length()))) {
                return null;
            }
        }
        return packet;
    }

    /** Custom NPCs requests have no server to answer them in a replay. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom
                && custom.payload().getClass().getName().startsWith(SERVER_PACKAGE);
    }
}
