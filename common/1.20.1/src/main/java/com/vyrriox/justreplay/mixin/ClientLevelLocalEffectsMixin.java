package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client plays the sounds and level events of its own player itself (footsteps, blocks placed and broken, eating,
 * swings): the server leaves that player out when it sends them. A recording only hears the server, so these are
 * handed to the recorder here, as the packets every other player received.
 *
 * @author vyrriox
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelLocalEffectsMixin {
    private static boolean justreplay$local(Player player) {
        return player != null && player == Minecraft.getInstance().player && (Recorder.isRecording() || Recorder.isBuffering());
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void justreplay$soundAt(Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume,
                                    float pitch, long seed, CallbackInfo ci) {
        if (justreplay$local(player)) {
            Recorder.recordLocalEffect(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed));
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void justreplay$soundOn(Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch,
                                    long seed, CallbackInfo ci) {
        if (justreplay$local(player)) {
            // The entity is often the local player, whose id differs in the replay: the sound is placed where it is.
            Recorder.recordLocalEffect(new ClientboundSoundPacket(sound, source, entity.getX(), entity.getY(), entity.getZ(), volume, pitch, seed));
        }
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void justreplay$levelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (justreplay$local(player)) {
            Recorder.recordLocalEffect(new ClientboundLevelEventPacket(type, pos, data, false));
        }
    }
}
