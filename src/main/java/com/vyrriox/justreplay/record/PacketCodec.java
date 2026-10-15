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
        Scratch scratch = SCRATCH.get();
        ByteBuf buf = scratch.busy ? Unpooled.buffer(256) : scratch.buf;
        boolean shared = buf == scratch.buf;
        scratch.busy |= shared;
        byte[] bytes;
        try {
            buf.clear();
            codec.encode(buf, (Packet<? super ClientGamePacketListener>) packet);
            bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
        } finally {
            if (shared) {
                scratch.busy = false;
                scratch.trim();
            } else {
                buf.release();
            }
        }
        sink.accept(bytes);
    }

    /**
     * Encoding buffer reused by each thread (network thread, client thread), so recording a packet allocates only
     * its final byte array. A buffer grown by a huge packet is given back.
     */
    private static final class Scratch {
        private static final int INITIAL = 8 * 1024;
        private static final int MAX_KEPT = 1 << 20;
        ByteBuf buf = Unpooled.buffer(INITIAL);
        boolean busy;

        void trim() {
            if (buf.capacity() > MAX_KEPT) {
                buf.release();
                buf = Unpooled.buffer(INITIAL);
            }
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    public Packet<? super ClientGamePacketListener> decode(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}
