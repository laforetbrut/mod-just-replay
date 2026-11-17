package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.replay.ReplaySession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bobby attaches its fake chunk manager to every client chunk cache created while it is enabled. A replay world
 * must show only recorded chunks and must never be saved into Bobby's cache (the replay host is a singleplayer
 * world, so every replay would share one cache entry), so Bobby reports itself disabled while a replay runs.
 * Optional: does nothing when Bobby is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "de.johni0702.minecraft.bobby.Bobby", remap = false)
public abstract class BobbyMixin {
    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$disableDuringReplay(CallbackInfoReturnable<Boolean> cir) {
        if (ReplaySession.current() != null) {
            cir.setReturnValue(false);
        }
    }
}
