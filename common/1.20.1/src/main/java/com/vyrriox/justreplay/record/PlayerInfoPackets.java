package com.vyrriox.justreplay.record;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Builds tab list and player spawn packets for arbitrary profiles. Vanilla only builds them from players that exist,
 * so the packet is written field by field and read back through its own constructor.
 *
 * @author vyrriox
 */
public final class PlayerInfoPackets {
    public record Info(UUID uuid, GameProfile profile, GameType gameMode, boolean listed, int latency, @Nullable Component displayName) {
    }

    private PlayerInfoPackets() {
    }

    public static ClientboundPlayerInfoUpdatePacket build(ClientPacketListener listener, List<Info> infos) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
            buf.writeEnumSet(actions, ClientboundPlayerInfoUpdatePacket.Action.class);
            buf.writeVarInt(infos.size());
            for (Info info : infos) {
                buf.writeUUID(info.uuid());
                String name = info.profile().getName();
                if (name.length() > 16) {
                    name = name.substring(0, 16);
                }
                buf.writeUtf(name, 16);
                buf.writeGameProfileProperties(info.profile().getProperties());
                buf.writeVarInt(info.gameMode().getId());
                buf.writeBoolean(info.listed());
                buf.writeVarInt(info.latency());
                buf.writeNullable(info.displayName(), FriendlyByteBuf::writeComponent);
            }
            return new ClientboundPlayerInfoUpdatePacket(buf);
        } finally {
            buf.release();
        }
    }

    /** The spawn packet of a player whose tab list entry is under {@code uuid}; players cannot come from an add entity packet. */
    public static ClientboundAddPlayerPacket addPlayer(int entityId, UUID uuid, double x, double y, double z, float yRot, float xRot) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(48));
        try {
            buf.writeVarInt(entityId);
            buf.writeUUID(uuid);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeByte((byte) (int) (yRot * 256.0F / 360.0F));
            buf.writeByte((byte) (int) (xRot * 256.0F / 360.0F));
            return new ClientboundAddPlayerPacket(buf);
        } finally {
            buf.release();
        }
    }
}
