package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every packet the client receives: feeds the recorder and silences the host server of a replay.
 *
 * @author vyrriox
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void justreplay$onPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (self.getReceiving() != PacketFlow.CLIENTBOUND) {
            return;
        }
        if (ReplaySession.shouldBlock(self)) {
            ci.cancel();
            return;
        }
        Recorder.onPacketReceived(self, packet);
    }

    /** The camera's movement is never sent to the replay host, so its server player never loads chunks. */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void justreplay$onSend(Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (self.getSending() == PacketFlow.SERVERBOUND && ReplaySession.shouldBlock(self)
                && (packet instanceof ServerboundMovePlayerPacket || com.vyrriox.justreplay.compat.ReplayCompat.blockServerbound(packet))) {
            ci.cancel();
        }
    }

    /** Mod handlers may disconnect on a payload they reject; during playback that would end the replay. */
    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"), cancellable = true)
    private void justreplay$onDisconnect(net.minecraft.network.DisconnectionDetails details, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (com.vyrriox.justreplay.replay.Playback.isDispatching() && ReplaySession.isReplayConnection(self)) {
            com.vyrriox.justreplay.JustReplay.LOGGER.warn("A recorded packet asked to disconnect the replay ({}), ignored",
                    details.reason().getString());
            ci.cancel();
        }
    }
}
