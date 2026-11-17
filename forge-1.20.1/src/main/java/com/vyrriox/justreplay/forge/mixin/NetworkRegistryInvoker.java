package com.vyrriox.justreplay.forge.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/**
 * Forge keeps the lookup of a registered channel to itself. A recorded payload whose channel is not registered here
 * would only log a warning per packet, so playback needs to ask.
 *
 * @author vyrriox
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public interface NetworkRegistryInvoker {
    @Invoker("findTarget")
    static Optional<?> justreplay$findTarget(ResourceLocation channel) {
        throw new AssertionError();
    }
}
