package com.vyrriox.justreplay.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the netty channel of a connection, a private field in vanilla.
 *
 * @author vyrriox
 */
@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    Channel justreplay$getChannel();
}
