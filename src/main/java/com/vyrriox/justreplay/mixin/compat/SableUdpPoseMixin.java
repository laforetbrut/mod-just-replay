package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.SableCompat;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sub-level poses a multiplayer server sends over Sable's own UDP channel, which the connection never sees: they are
 * recorded here. Optional: does nothing when Sable is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.udp.handler.SableUDPChannelHandlerClient", remap = false)
public abstract class SableUdpPoseMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Ldev/ryanhcode/sable/network/udp/AddressedSableUDPPacket;)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void justreplay$received(ChannelHandlerContext context, @Coerce Object message, CallbackInfo ci) {
        SableCompat.onUdpReceived(message);
    }
}
