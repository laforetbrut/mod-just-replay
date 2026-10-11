package com.vyrriox.justreplay.record;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.function.Consumer;

/**
 * Encodes and decodes clientbound play packets with the same codec the network stack uses, including the
 * custom payloads of every installed mod.
 *
 * @author vyrriox
 */
public final class PacketCodec {
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private final RegistryAccess registryAccess;
    private final ConnectionType connectionType;

    public PacketCodec(RegistryAccess registryAccess, ConnectionType connectionType) {
        this.registryAccess = registryAccess;
        this.connectionType = connectionType;
        this.codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess, connectionType)).codec();
    }

    public static PacketCodec of(ClientPacketListener listener) {
        return new PacketCodec(listener.registryAccess(), listener.getConnectionType());
    }

    public boolean matches(ClientPacketListener listener) {
        return listener.registryAccess() == registryAccess && listener.getConnectionType() == connectionType;
    }

    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    public ConnectionType connectionType() {
        return connectionType;
    }

    /** Encodes a packet, splitting bundles into their sub packets. */
    @SuppressWarnings("unchecked")
    public void encode(Packet<?> packet, Consumer<byte[]> sink) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                encode(sub, sink);
            }
            return;
        }
        ByteBuf buf = Unpooled.buffer(256);
        try {
            codec.encode(buf, (Packet<? super ClientGamePacketListener>) packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            sink.accept(bytes);
        } finally {
            buf.release();
        }
    }

    public Packet<? super ClientGamePacketListener> decode(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}
