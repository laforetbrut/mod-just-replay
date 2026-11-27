package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Payloads;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Trinkets slots (capes, rings, belts, the elytra of Elytra Slot) in snapshots and checkpoints. What an entity wears
 * reaches a client once, in the Cardinal Components sync of that entity (when tracking starts), then only as changes
 * ({@code trinkets:sync_inventory}, keyed by entity id): a recording started mid-session, and a seek that rebuilds the
 * entities, would show nobody's trinkets until the next change. The current content of an entity is written as such a
 * change message; the recording player's actor shares the player's entity id and gets it too.
 * <p>
 * Trinkets gives an entity slots from the definitions of the server played on ({@code trinkets:sync_slots}, sent once
 * at login), and drops the content of a slot the entity does not have: snapshots and checkpoints carry the
 * definitions first. Trinkets sends nothing to the server. Everything is reached by reflection (Trinkets 3.7.2).
 *
 * @author vyrriox
 */
public final class TrinketsCompat {
    private static final ResourceLocation SYNC_INVENTORY = new ResourceLocation("trinkets", "sync_inventory");
    private static final ResourceLocation SYNC_SLOTS = new ResourceLocation("trinkets", "sync_slots");
    private static Reflect reflect;
    private static boolean reflectFailed;
    private static boolean definitionsFailed;

    private TrinketsCompat() {
    }

    private record Reflect(Method getComponent, Method getInventory, Method getSyncTag, Object clientSlots,
                           Method getSlotsPacket, Field slots, Method setSlots) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !Integrations.TRINKETS.on()) {
            return reflect;
        }
        try {
            ClassLoader loader = TrinketsCompat.class.getClassLoader();
            Class<?> api = Class.forName("dev.emi.trinkets.api.TrinketsApi", false, loader);
            Class<?> component = Class.forName("dev.emi.trinkets.api.TrinketComponent", false, loader);
            Class<?> inventory = Class.forName("dev.emi.trinkets.api.TrinketInventory", false, loader);
            Class<?> slotLoader = Class.forName("dev.emi.trinkets.data.EntitySlotLoader", false, loader);
            Method getSlotsPacket = slotLoader.getDeclaredMethod("getSlotsPacket");
            getSlotsPacket.setAccessible(true);
            Field slots = slotLoader.getDeclaredField("slots");
            slots.setAccessible(true);
            reflect = new Reflect(api.getMethod("getTrinketComponent", LivingEntity.class), component.getMethod("getInventory"),
                    inventory.getMethod("getSyncTag"), slotLoader.getField("CLIENT").get(null), getSlotsPacket, slots,
                    slotLoader.getMethod("setSlots", Map.class));
            JustReplay.LOGGER.info("Trinkets detected: worn trinkets are recorded in snapshots and checkpoints");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            Integrations.TRINKETS.fail("reflect", t);
        }
        return reflect;
    }

    /** The inventories of an entity, group to slot to inventory, or null when Trinkets gives it no component. */
    private static Map<?, ?> inventories(Reflect r, LivingEntity entity) throws ReflectiveOperationException {
        Optional<?> component = (Optional<?>) r.getComponent.invoke(null, entity);
        return component.isPresent() ? (Map<?, ?>) r.getInventory.invoke(component.get()) : null;
    }

    /**
     * Hands every stack an entity wears in a Trinkets slot to {@code out}, named {@code trinkets:<group>/<slot>}, with
     * {@code #<n>} from the second place of a slot on (the MCP observation tools). Nothing when Trinkets is absent or
     * gives the entity no slots.
     */
    static void forEachWorn(Entity entity, BiConsumer<String, ItemStack> out) throws ReflectiveOperationException {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return;
        }
        Map<?, ?> groups = inventories(r, living);
        if (groups == null) {
            return;
        }
        for (Map.Entry<?, ?> group : groups.entrySet()) {
            for (Map.Entry<?, ?> slot : ((Map<?, ?>) group.getValue()).entrySet()) {
                String name = "trinkets:" + group.getKey() + "/" + slot.getKey();
                Container stacks = (Container) slot.getValue();
                for (int i = 0; i < stacks.getContainerSize(); i++) {
                    ItemStack stack = stacks.getItem(i);
                    if (!stack.isEmpty()) {
                        out.accept(i == 0 ? name : name + "#" + (i + 1), stack);
                    }
                }
            }
        }
    }

    /**
     * Writes the slot definitions the client holds now (snapshots and checkpoints), in the form of the server's own
     * sync. It must come before the worn trinkets of any entity.
     */
    static void writeDefinitions(Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || definitionsFailed) {
            return;
        }
        try {
            out.accept(Payloads.make(SYNC_SLOTS, (FriendlyByteBuf) r.getSlotsPacket.invoke(r.clientSlots)));
        } catch (Throwable t) {
            definitionsFailed = true;
            ReplayCompat.warnOnce("trinkets.definitions", "Cannot record the Trinkets slot definitions", t);
        }
    }

    /**
     * Writes what an entity wears now (snapshots and checkpoints), as the server sends it: the state of each of its
     * slots (their size changes with modifiers), then every place of every slot. Nothing when it wears nothing.
     */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            Map<?, ?> groups = inventories(r, living);
            if (groups == null) {
                return;
            }
            CompoundTag slots = new CompoundTag();
            CompoundTag contents = new CompoundTag();
            boolean worn = false;
            for (Map.Entry<?, ?> group : groups.entrySet()) {
                for (Map.Entry<?, ?> slot : ((Map<?, ?>) group.getValue()).entrySet()) {
                    String key = group.getKey() + "/" + slot.getKey();
                    slots.put(key, (CompoundTag) r.getSyncTag.invoke(slot.getValue()));
                    Container stacks = (Container) slot.getValue();
                    for (int i = 0; i < stacks.getContainerSize(); i++) {
                        ItemStack stack = stacks.getItem(i);
                        worn |= !stack.isEmpty();
                        contents.put(key + "/" + i, stack.save(new CompoundTag()));
                    }
                }
            }
            if (worn) {
                FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
                bytes.writeInt(entity.getId());
                bytes.writeNbt(slots);
                bytes.writeNbt(contents);
                out.accept(Payloads.make(SYNC_INVENTORY, bytes));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("trinkets.snapshot", "Cannot record the Trinkets of " + entity, t);
        }
    }

    /**
     * Self test: empties the slot definitions of the client, as a game just started has them, and returns what they
     * were (null when Trinkets is absent). The seek that follows must bring them back from the recording.
     */
    public static Map<?, ?> clearClientSlots() {
        Reflect r = reflect();
        if (r == null) {
            return null;
        }
        try {
            Map<?, ?> before = new HashMap<>((Map<?, ?>) r.slots.get(r.clientSlots));
            r.setSlots.invoke(r.clientSlots, new HashMap<>());
            return before;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("trinkets.clear", "Cannot clear the Trinkets slot definitions", t);
            return null;
        }
    }

    /** Self test: how many entity types the client has slot definitions for now, -1 when Trinkets is absent. */
    public static int clientSlotTypes() {
        Reflect r = reflect();
        if (r == null) {
            return -1;
        }
        try {
            return ((Map<?, ?>) r.slots.get(r.clientSlots)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Self test: puts back the definitions {@link #clearClientSlots} returned. */
    public static void restoreClientSlots(Map<?, ?> slots) {
        Reflect r = reflect();
        if (r != null && slots != null) {
            try {
                r.setSlots.invoke(r.clientSlots, slots);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("trinkets.restore", "Cannot restore the Trinkets slot definitions", t);
            }
        }
    }
}
