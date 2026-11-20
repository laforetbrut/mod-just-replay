package com.vyrriox.justreplay.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the self-test move the mouse as the window would, to prove an export ignores it.
 *
 * @author vyrriox
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("accumulatedDX")
    void justreplay$setAccumulatedDX(double value);

    @Accessor("accumulatedDY")
    void justreplay$setAccumulatedDY(double value);

    @Invoker("turnPlayer")
    void justreplay$turnPlayer();
}
