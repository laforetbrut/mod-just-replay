package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.resources.sounds.MinecartSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A frozen minecart keeps its speed but makes no rolling sound: it counts as standing still.
 *
 * @author vyrriox
 */
@Mixin(MinecartSoundInstance.class)
public abstract class FreezeMinecartSoundMixin {
    @ModifyVariable(method = "tick", at = @At("STORE"), ordinal = 0)
    private float justreplay$speed(float speed) {
        return TimeControl.isFrozen() ? 0.0F : speed;
    }
}
