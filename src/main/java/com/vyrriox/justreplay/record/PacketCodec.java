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

    /** Id of the clientbound custom payload packet in this codec, found once by encoding one; -2 until known, -1 if unknown. */
    private int customPayloadId = -2;

    /**
     * The id of a recorded mod payload this client cannot decode (its mod is not installed or does not receive it on
     * the client), read from the raw bytes; null for any other packet. Decoding such a payload would log a warning per
     * packet and give a payload nothing handles.
     */
    public net.minecraft.resources.ResourceLocation unregisteredPayload(byte[] data) {
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
            net.minecraft.resources.ResourceLocation id = buf.readResourceLocation();
            return PayloadIds.receivable(id) ? null : id;
        } catch (Throwable t) {
            return null;
        } finally {
            buf.release();
        }
    }

    /** Mod payload ids the client can decode, read from NeoForge's payload registry. */
    private static final class PayloadIds {
        private static java.util.Set<net.minecraft.resources.ResourceLocation> known;
        private static boolean failed;

        private PayloadIds() {
        }

        static synchronized boolean receivable(net.minecraft.resources.ResourceLocation id) {
            if ("minecraft".equals(id.getNamespace())) {
                return true;
            }
            if (known == null && !failed) {
                try {
                    known = load();
                } catch (Throwable t) {
                    failed = true;
                    com.vyrriox.justreplay.JustReplay.LOGGER.debug("Payload registry not readable: {}", t.toString());
                }
            }
            return known == null || known.contains(id);
        }

        private static java.util.Set<net.minecraft.resources.ResourceLocation> load() throws ReflectiveOperationException {
            Class<?> registry = net.neoforged.neoforge.network.registration.NetworkRegistry.class;
            java.util.Set<net.minecraft.resources.ResourceLocation> ids = new java.util.HashSet<>();
            java.lang.reflect.Field builtin = registry.getDeclaredField("BUILTIN_PAYLOADS");
            builtin.setAccessible(true);
            for (Object key : ((java.util.Map<?, ?>) builtin.get(null)).keySet()) {
                ids.add((net.minecraft.resources.ResourceLocation) key);
            }
            java.lang.reflect.Field field = registry.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            Object play = ((java.util.Map<?, ?>) field.get(null)).get(net.minecraft.network.ConnectionProtocol.PLAY);
            if (play instanceof java.util.Map<?, ?> registrations) {
                for (java.util.Map.Entry<?, ?> entry : registrations.entrySet()) {
                    java.lang.reflect.Method flowOf = entry.getValue().getClass().getMethod("flow");
                    flowOf.setAccessible(true);
                    Object flow = flowOf.invoke(entry.getValue());
                    if (flow instanceof java.util.Optional<?> optional && optional.isPresent()
                            && optional.get() != net.minecraft.network.protocol.PacketFlow.CLIENTBOUND) {
                        continue;
                    }
                    ids.add((net.minecraft.resources.ResourceLocation) entry.getKey());
                }
            }
            return ids;
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
