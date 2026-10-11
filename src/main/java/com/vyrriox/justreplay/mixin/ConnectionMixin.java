package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
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
}
