package com.vyrriox.justreplay.record;

import com.vyrriox.justreplay.platform.ClientPlatform;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GameProtocols;

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
    private final Object connectionToken;

    private PacketCodec(ClientPacketListener listener) {
        this.registryAccess = listener.registryAccess();
        this.connectionToken = ClientPlatform.get().connectionToken(listener);
        this.codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(ClientPlatform.get().bufDecorator(listener)).codec();
    }

    public static PacketCodec of(ClientPacketListener listener) {
        return new PacketCodec(listener);
    }

    public boolean matches(ClientPacketListener listener) {
        return listener.registryAccess() == registryAccess && ClientPlatform.get().connectionToken(listener) == connectionToken;
    }

    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    /** Called with a bundle's sub packet and the error when that sub packet cannot be encoded; set by the recorder. */
    private volatile java.util.function.BiConsumer<Packet<?>, Throwable> onSubFailure;

    public void onSubPacketFailure(java.util.function.BiConsumer<Packet<?>, Throwable> handler) {
        this.onSubFailure = handler;
    }

    /** Encodes a packet, splitting bundles into their sub packets. */
    @SuppressWarnings("unchecked")
    public void encode(Packet<?> packet, Consumer<byte[]> sink) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                try {
                    encode(sub, sink);
                } catch (Throwable t) {
                    // One bad sub packet must not lose the rest of the bundle.
                    if (onSubFailure != null) {
                        onSubFailure.accept(sub, t);
                    }
                }
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
        sink.accept(com.vyrriox.justreplay.compat.ReplayCompat.patchEncoded(packet, bytes));
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

    /** Id of the clientbound custom payload packet in this codec, found once by encoding one; -2 until known, -1 if unknown. */
    private int customPayloadId = -2;

    /**
     * The id of a recorded mod payload this client cannot decode (its mod is not installed or does not receive it on
     * the client), read from the raw bytes; null for any other packet. Decoding such a payload would log a warning per
     * packet and give a payload nothing handles.
     */
    public net.minecraft.resources.Identifier unregisteredPayload(byte[] data) {
        if (customPayloadId == -2) {
            customPayloadId = -1;
            try {
                byte[][] probe = new byte[1][];
                encode(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new net.minecraft.network.protocol.common.custom.BrandPayload("justreplay")), b -> probe[0] = b);
                customPayloadId = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(probe[0])).readVarInt();
            } catch (Throwable t) {
                return null;
            }
        }
        if (customPayloadId < 0) {
            return null;
        }
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            if (buf.readVarInt() != customPayloadId) {
                return null;
            }
            net.minecraft.resources.Identifier id = buf.readResourceLocation();
            return ClientPlatform.get().canDecodePayload(id) ? null : id;
        } catch (Throwable t) {
            return null;
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
