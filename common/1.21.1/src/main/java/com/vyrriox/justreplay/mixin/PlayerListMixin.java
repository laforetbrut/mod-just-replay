package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The server hosting a replay only holds an empty world: keep its view and simulation distances minimal so it
 * loads almost nothing and shuts down instantly.
 *
 * @author vyrriox
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @ModifyVariable(method = "setViewDistance", at = @At("HEAD"), argsOnly = true)
    private int justreplay$hostViewDistance(int distance) {
        return ReplaySession.isHostServer(server) ? 2 : distance;
    }

    @ModifyVariable(method = "setSimulationDistance", at = @At("HEAD"), argsOnly = true)
    private int justreplay$hostSimulationDistance(int distance) {
        return ReplaySession.isHostServer(server) ? 2 : distance;
    }
}
