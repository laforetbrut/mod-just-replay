package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Curios slots (backpacks, rings, belts, capes) in snapshots and checkpoints. The Curios server sends the full slot
 * content of an entity only at login, when tracking starts and at a datapack sync: a recording started mid-session
 * (and a seek that rebuilds the entities) would show nobody's curios until the next change. The current content is
 * written as the server sends it ({@code curios:sync_curios}, keyed by entity id, so the recording player's actor,
 * which shares the id, gets it too). Everything is reached by reflection.
 *
 * @author vyrriox
 */
public final class CuriosCompat {
    private static Reflect reflect;
    private static boolean reflectFailed;

    private CuriosCompat() {
    }

    private record Reflect(Method getInventory, Method getCurios, Method getStacks, Method getCosmeticStacks,
                           Method getSlots, Method getStackInSlot, Constructor<?> payloadNew) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.CURIOS) {
            return reflect;
        }
        try {
            ClassLoader loader = CuriosCompat.class.getClassLoader();
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi", false, loader);
            Class<?> inventory = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler", false, loader);
            Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler", false, loader);
            Class<?> stacks = Class.forName("top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler", false, loader);
            Class<?> payload = Class.forName("top.theillusivec4.curios.common.network.server.sync.SPacketSyncCurios", false, loader);
            reflect = new Reflect(api.getMethod("getCuriosInventory", LivingEntity.class), inventory.getMethod("getCurios"),
                    handler.getMethod("getStacks"), handler.getMethod("getCosmeticStacks"),
                    stacks.getMethod("getSlots"), stacks.getMethod("getStackInSlot", int.class),
                    payload.getConstructor(int.class, Map.class));
            JustReplay.LOGGER.info("Curios detected: worn curios are recorded in snapshots and checkpoints");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("curios.reflect", "Curios support disabled, its classes changed", t);
        }
        return reflect;
    }

    /** The slot handlers of an entity (slot type to handler), or null when it has no curios inventory. */
    private static Map<?, ?> curios(Reflect r, LivingEntity entity) throws ReflectiveOperationException {
        Optional<?> inventory = (Optional<?>) r.getInventory.invoke(null, entity);
        return inventory.isPresent() ? (Map<?, ?>) r.getCurios.invoke(inventory.get()) : null;
    }

    private static int count(Reflect r, Object stacks) throws ReflectiveOperationException {
        int count = 0;
        int slots = (int) r.getSlots.invoke(stacks);
        for (int i = 0; i < slots; i++) {
            if (!((ItemStack) r.getStackInSlot.invoke(stacks, i)).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int count(Reflect r, Map<?, ?> curios) throws ReflectiveOperationException {
        int count = 0;
        for (Object handler : curios.values()) {
            count += count(r, r.getStacks.invoke(handler)) + count(r, r.getCosmeticStacks.invoke(handler));
        }
        return count;
    }

    /** Non-empty curio and cosmetic stacks an entity wears now, or -1 when Curios is absent or has no inventory for it. */
    public static int wornCount(Entity entity) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return -1;
        }
        try {
            Map<?, ?> curios = curios(r, living);
            return curios == null ? -1 : count(r, curios);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("curios.count", "Cannot read the Curios of " + entity, t);
            return -1;
        }
    }

    /** Writes what an entity wears now (snapshots and checkpoints), as the server sends it. Nothing when it wears none. */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            Map<?, ?> curios = curios(r, living);
            if (curios != null && count(r, curios) > 0) {
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.payloadNew.newInstance(entity.getId(), curios)));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("curios.snapshot", "Cannot record the Curios of " + entity, t);
        }
    }
}
