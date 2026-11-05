package com.vyrriox.justreplay.record;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Custom payload packets of this Minecraft version: a channel id and raw bytes, in both directions. Later versions
 * type the payload instead, so everything that reads or builds one goes through here.
 *
 * @author vyrriox
 */
public final class Payloads {
    private Payloads() {
    }

    /** True for a custom payload packet sent to the client. */
    public static boolean isClientbound(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket;
    }

    /** True for a custom payload packet sent to the server. */
    public static boolean isServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket;
    }

    /** The channel of a custom payload packet of either direction, null for any other packet. */
    @Nullable
    public static ResourceLocation idOf(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            return custom.getIdentifier();
        }
        if (packet instanceof ServerboundCustomPayloadPacket custom) {
            return custom.getIdentifier();
        }
        return null;
    }

    /**
     * A private copy of the bytes of a custom payload packet of either direction, null for any other packet. The copy
     * is the caller's: read it, then release it.
     */
    @Nullable
    public static FriendlyByteBuf bytes(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            return custom.getData();
        }
        if (packet instanceof ServerboundCustomPayloadPacket custom) {
            return new FriendlyByteBuf(custom.getData().copy());
        }
        return null;
    }

    /** A clientbound custom payload packet; the packet reads {@code bytes} without consuming or releasing them. */
    public static ClientboundCustomPayloadPacket make(ResourceLocation id, FriendlyByteBuf bytes) {
        return new ClientboundCustomPayloadPacket(id, bytes);
    }

    /** A clientbound custom payload packet over a copy of {@code bytes}. */
    public static ClientboundCustomPayloadPacket make(ResourceLocation id, byte[] bytes) {
        return new ClientboundCustomPayloadPacket(id, new FriendlyByteBuf(Unpooled.copiedBuffer(bytes)));
    }

    /** A serverbound custom payload packet; the packet reads {@code bytes} without consuming or releasing them. */
    public static ServerboundCustomPayloadPacket makeServerbound(ResourceLocation id, FriendlyByteBuf bytes) {
        return new ServerboundCustomPayloadPacket(id, bytes);
    }
}
