package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.Visuals;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particles of the World tab: a hidden type is never created, "None" drops every particle whatever made it, and the
 * bits of broken blocks follow the {@code minecraft:block} type.
 *
 * @author vyrriox
 */
@Mixin(ParticleEngine.class)
public abstract class VisualParticlesMixin {
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"), cancellable = true)
    private void justreplay$hiddenType(ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed,
                                       CallbackInfoReturnable<Particle> cir) {
        if (Visuals.particleHidden(options)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void justreplay$noParticles(Particle particle, CallbackInfo ci) {
        if (Visuals.noParticles()) {
            ci.cancel();
        }
    }

    @Inject(method = "destroy(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"), cancellable = true)
    private void justreplay$hiddenBreak(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (Visuals.blockParticlesHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "crack(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V", at = @At("HEAD"), cancellable = true)
    private void justreplay$hiddenCrack(BlockPos pos, Direction side, CallbackInfo ci) {
        if (Visuals.blockParticlesHidden()) {
            ci.cancel();
        }
    }
}
