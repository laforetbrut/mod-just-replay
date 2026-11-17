package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.SableCompat;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable's integrated server hands sub-level poses to the singleplayer owner directly, never through the connection:
 * they are recorded here, and the replay world's own server is kept from sending any. Optional: does nothing when
 * Sable is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.udp.SableUDPServer", remap = false)
public abstract class SableLocalPoseMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "sendUDPPacketLocal(Ldev/ryanhcode/sable/network/udp/SableUDPPacket;)V", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void justreplay$sendLocal(@Coerce Object packet, CallbackInfo ci) {
        if (SableCompat.onLocalSend(server, packet)) {
            ci.cancel();
        }
    }
}
