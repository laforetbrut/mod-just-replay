package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.WaterFramesCompat;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A WaterFrames control packet (pause, time, loop...) changed a display: during a replay its play time is anchored
 * again to the recorded tick of that packet. Optional: does nothing when WaterFrames is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "me.srrapero720.waterframes.common.network.packets.DisplayControlPacket", remap = false)
public abstract class WaterFramesControlMixin {
    @Inject(method = "execute(Lme/srrapero720/waterframes/common/block/entity/DisplayTile;Z)V", at = @At("TAIL"),
            require = 0, remap = false)
    private void justreplay$executed(@Coerce BlockEntity tile, boolean server, CallbackInfo ci) {
        WaterFramesCompat.onDataChanged(tile);
    }
}
