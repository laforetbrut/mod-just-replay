package com.vyrriox.justreplay.record;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.connection.ConnectionType;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Builds tab list packets for arbitrary profiles. Vanilla only builds them from server players, so the packet
 * is written field by field and read back through its own codec.
 *
 * @author vyrriox
 */
public final class PlayerInfoPackets {
    public record Info(UUID uuid, GameProfile profile, GameType gameMode, boolean listed, int latency, @Nullable Component displayName) {
    }

    private PlayerInfoPackets() {
    }

    public static ClientboundPlayerInfoUpdatePacket build(RegistryAccess access, ConnectionType type, List<Info> infos) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access, type);
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
                ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buf, info.profile().getProperties());
                buf.writeVarInt(info.gameMode().getId());
                buf.writeBoolean(info.listed());
                buf.writeVarInt(info.latency());
                FriendlyByteBuf.writeNullable(buf, info.displayName(), ComponentSerialization.TRUSTED_STREAM_CODEC);
            }
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
