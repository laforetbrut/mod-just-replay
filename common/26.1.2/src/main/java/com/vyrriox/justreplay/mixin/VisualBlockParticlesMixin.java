package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.Visuals;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bits of broken and cracking blocks follow the {@code minecraft:block} particle type of the World tab.
 *
 * @author vyrriox
 */
@Mixin(ClientLevel.class)
public abstract class VisualBlockParticlesMixin {
    @Inject(method = "addDestroyBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"), cancellable = true)
    private void justreplay$hiddenBreak(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (Visuals.blockParticlesHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "addBreakingBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V", at = @At("HEAD"), cancellable = true)
    private void justreplay$hiddenCrack(BlockPos pos, Direction side, CallbackInfo ci) {
        if (Visuals.blockParticlesHidden()) {
            ci.cancel();
        }
    }
}
