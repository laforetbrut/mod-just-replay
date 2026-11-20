package com.vyrriox.justreplay.mixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Leaving a world never waits for a server that already stopped. The integrated server stops by itself as soon as its
 * player leaves, and the game then halts it again: that last step hands it one more task and waits for it, forever
 * once its thread is gone. Leaving a replay that wore a server resource pack reloads the resources in between, long
 * enough for the replay host to be gone, which froze the game on Fabric. NeoForge already has this very guard.
 *
 * @author vyrriox
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerHaltMixin {
    @Redirect(method = "halt", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void justreplay$skipStoppedServer(IntegratedServer server, Runnable task) {
        if (server.isRunning()) {
            server.executeBlocking(task);
        }
    }
}
