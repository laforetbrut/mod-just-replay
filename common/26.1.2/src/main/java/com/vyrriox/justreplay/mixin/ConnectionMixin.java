package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
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
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void justreplay$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket && ReplaySession.current() != null
                && ((Connection) (Object) this).getSending() == PacketFlow.CLIENTBOUND) {
            // The replay world's own server has nothing to say about recipes (the recording carries its own), and a
            // recipe it cannot encode would close the replay.
            ci.cancel();
            return;
        }
        Connection self = (Connection) (Object) this;
        if (self.getSending() != PacketFlow.SERVERBOUND) {
            // Cancelled before it is encoded: the registry ids are about to change under the host, see ReplaySession.
            if (ReplaySession.isHostSilenced() && !(packet instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket)) {
                ci.cancel();
            }
            return;
        }
        if (ReplaySession.shouldBlock(self)) {
            if (packet instanceof ServerboundMovePlayerPacket || com.vyrriox.justreplay.compat.ReplayCompat.blockServerbound(packet)) {
                ci.cancel();
            }
        } else if (!ReplaySession.isReplayConnection(self)) {
            com.vyrriox.justreplay.compat.ReplayCompat.onLiveServerbound(packet);
        }
    }

    /**
     * Mod handlers may disconnect on a payload they reject; during playback that would end the replay. Only on the
     * render thread, where recorded packets are handled: a fault of the channel itself is not caused by one of them.
     */
    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"), cancellable = true)
    private void justreplay$onDisconnect(net.minecraft.network.DisconnectionDetails details, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (com.vyrriox.justreplay.replay.Playback.isDispatching() && net.minecraft.client.Minecraft.getInstance().isSameThread()
                && ReplaySession.isReplayConnection(self)) {
            com.vyrriox.justreplay.JustReplay.LOGGER.warn("A recorded packet asked to disconnect the replay ({}), ignored",
                    details.reason().getString());
            ci.cancel();
        }
    }
}
