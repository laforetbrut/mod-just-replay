package com.vyrriox.justreplay.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the weather levels of a level, protected in vanilla, without an access transformer.
 *
 * @author vyrriox
 */
@Mixin(Level.class)
public interface LevelWeatherAccessor {
    @Accessor("rainLevel")
    float justreplay$getRainLevel();

    @Accessor("thunderLevel")
    float justreplay$getThunderLevel();
}
