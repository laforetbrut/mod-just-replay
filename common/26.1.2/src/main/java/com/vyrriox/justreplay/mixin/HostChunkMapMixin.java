package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The world hosting a replay is thrown away when the replay ends, so it is never saved on shutdown. Saving it
 * could wait forever on a chunk that never becomes ready to save, which froze the game when leaving a replay.
 *
 * @author vyrriox
 */
@Mixin(ChunkMap.class)
public abstract class HostChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    private boolean justreplay$hostStopping() {
        return ReplaySession.isHostServer(level.getServer()) && !level.getServer().isRunning();
    }

    @Inject(method = "hasWork", at = @At("HEAD"), cancellable = true)
    private void justreplay$noWorkWhenHostStops(CallbackInfoReturnable<Boolean> cir) {
        if (justreplay$hostStopping()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void justreplay$noSaveWhenHostStops(boolean flush, CallbackInfo ci) {
        if (justreplay$hostStopping()) {
            ci.cancel();
        }
    }
}
