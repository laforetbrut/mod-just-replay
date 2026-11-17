package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Immersive Aircraft equipment (weapons, banners, dyed sails) in recordings and replays.
 * <ul>
 *     <li>An aircraft asks the server for its inventory once, when it is created on the client. An aircraft already in
 *     view when the recording starts, or one rebuilt by a seek, never gets an answer in a replay: snapshots and
 *     checkpoints write the slots as the server would.</li>
 *     <li>The aircraft's requests to the replay host are blocked: it has no such entity.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Immersive Aircraft class when the mod is absent.
 *
 * @author vyrriox
 */
public final class ImmersiveAircraftCompat {
    private static final String C2S = "immersive_aircraft.network.c2s.";
    private static Reflect reflect;
    private static boolean reflectFailed;

    private ImmersiveAircraftCompat() {
    }

    private record Reflect(Class<?> vehicle, Method getInventory, Constructor<?> update) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.IMMERSIVE_AIRCRAFT) {
            return reflect;
        }
        try {
            ClassLoader loader = ImmersiveAircraftCompat.class.getClassLoader();
            Class<?> vehicle = Class.forName("immersive_aircraft.entity.InventoryVehicleEntity", false, loader);
            Class<?> update = Class.forName("immersive_aircraft.network.s2c.InventoryUpdateMessage", false, loader);
            reflect = new Reflect(vehicle, vehicle.getMethod("getInventory"),
                    update.getConstructor(Entity.class, int.class, ItemStack.class));
            JustReplay.LOGGER.info("Immersive Aircraft detected: aircraft equipment is recorded and replayed");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("aircraft.reflect", "Immersive Aircraft support disabled, its classes changed", t);
        }
        return reflect;
    }

    /** Writes the filled slots of an aircraft (snapshots and checkpoints), as the server answers an inventory request. */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || entity == null || !r.vehicle.isInstance(entity)) {
            return;
        }
        try {
            Container inventory = (Container) r.getInventory.invoke(entity);
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.update.newInstance(entity, i, stack)));
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("aircraft.snapshot", "Cannot record the equipment of " + entity, t);
        }
    }

    /** Number of filled slots of an aircraft, or -1 when the entity is not one. */
    public static int filledSlots(Entity entity) {
        Reflect r = reflect();
        if (r == null || entity == null || !r.vehicle.isInstance(entity)) {
            return -1;
        }
        try {
            Container inventory = (Container) r.getInventory.invoke(entity);
            int filled = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (!inventory.getItem(i).isEmpty()) {
                    filled++;
                }
            }
            return filled;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The aircraft's requests (inventory, input, fire) go to a replay host that has no such entity. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && custom.payload().getClass().getName().startsWith(C2S);
    }
}
