package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client ignores everything the replay host sends, keep alive packets included, and this version also times out
 * the singleplayer owner: without this the host kicks the camera player 30 seconds into any replay, which stops the
 * host and ends the replay. The challenge is never due on the host, as later versions do for the owner.
 *
 * @author vyrriox
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class HostKeepAliveMixin {
    @Shadow
    @Final
    private MinecraftServer server;
    @Shadow
    private long keepAliveTime;

    @Inject(method = "tick", at = @At("HEAD"))
    private void justreplay$noKeepAliveOnHost(CallbackInfo ci) {
        if (ReplaySession.isHostServer(server)) {
            this.keepAliveTime = Util.getMillis();
        }
    }
}
