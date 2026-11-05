package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A frozen level holds its block entity tickers. Only the tick is skipped: new block entities still get loaded and
 * removed ones dropped.
 *
 * @author vyrriox
 */
@Mixin(Level.class)
public abstract class FreezeLevelMixin {
    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;shouldTickBlocksAt(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean justreplay$holdBlockEntity(Level level, BlockPos pos) {
        if (TimeControl.isFrozen(level)) {
            TimeControl.countHeldBlockEntityTick();
            return false;
        }
        return level.shouldTickBlocksAt(pos);
    }
}
