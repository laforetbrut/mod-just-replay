package com.vyrriox.justreplay.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the self-test press a key as the window would, to prove an export ignores it.
 *
 * @author vyrriox
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void justreplay$keyPress(long window, int action, KeyEvent event);
}
