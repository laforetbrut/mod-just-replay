package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stops the integrated server from syncing the client render distance onto the replay host every tick.
 *
 * @author vyrriox
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @ModifyVariable(method = "tickServer", at = @At("STORE"), ordinal = 0)
    private int justreplay$hostViewDistance(int distance) {
        return ReplaySession.isHostServer((IntegratedServer) (Object) this) ? 2 : distance;
    }

    @ModifyVariable(method = "tickServer", at = @At("STORE"), ordinal = 1)
    private int justreplay$hostSimulationDistance(int distance) {
        return ReplaySession.isHostServer((IntegratedServer) (Object) this) ? 2 : distance;
    }
}
