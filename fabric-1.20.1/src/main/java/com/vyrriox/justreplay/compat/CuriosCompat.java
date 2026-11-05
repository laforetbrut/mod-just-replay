package com.vyrriox.justreplay.compat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;

import java.util.function.Consumer;

/**
 * Stand-in for the Curios integration of the common code, which is written against Forge's network and capability
 * classes. Curios has no Fabric build for this Minecraft version, so {@link ReplayCompat} never calls these methods here.
 *
 * @author vyrriox
 */
public final class CuriosCompat {
    private CuriosCompat() {
    }

    public static int wornCount(Entity entity) {
        return 0;
    }

    static void writeDefinitions(Consumer<Packet<?>> out) {
    }

    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
    }
}
