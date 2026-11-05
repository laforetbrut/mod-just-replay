package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.WaterFramesCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WaterFrames display data was loaded from a tag (chunk, block entity update or data sync packet): during a replay its
 * play time is anchored to the recorded tick of that packet. Optional: does nothing when WaterFrames is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "me.srrapero720.waterframes.common.block.data.DisplayData", remap = false)
public abstract class WaterFramesDataMixin {
    @Inject(method = "load", at = @At("TAIL"), require = 0, remap = false)
    private void justreplay$loaded(CompoundTag tag, @Coerce BlockEntity tile, CallbackInfo ci) {
        WaterFramesCompat.onDataChanged(tile);
    }
}
