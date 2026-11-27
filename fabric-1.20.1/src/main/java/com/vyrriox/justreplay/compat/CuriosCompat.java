package com.vyrriox.justreplay.compat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;
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

    public static int slotTypes(Entity entity) {
        return -1;
    }

    public static boolean hasSlotType(Entity entity, String slot) {
        return false;
    }

    static void forEachWorn(Entity entity, BiConsumer<String, ItemStack> out) {
    }

    public static void rebuildSlots(Entity entity) {
    }

    static void writeDefinitions(Consumer<Packet<?>> out) {
    }

    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
    }
}
