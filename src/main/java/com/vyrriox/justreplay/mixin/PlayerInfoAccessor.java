package com.vyrriox.justreplay.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets playback keep the camera's tab list entry in spectator mode.
 *
 * @author vyrriox
 */
@Mixin(PlayerInfo.class)
public interface PlayerInfoAccessor {
    @Invoker("setGameMode")
    void justreplay$setGameMode(GameType gameMode);
}
