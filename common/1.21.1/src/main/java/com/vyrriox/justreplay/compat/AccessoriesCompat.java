package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Accessories slots in snapshots and checkpoints. The server sends the full content of a holder only when tracking
 * starts and at a datapack sync, and the slot definitions only at a datapack sync, which the replay host never does. A
 * holder read without the definitions moves every stack to its invalid list, so a snapshot writes the definitions the
 * client has now first, then the holders as the server sends them, keyed by entity id: the recording player's actor,
 * which shares the id, gets its own. Everything is reached by reflection: nothing here loads a class of the mod when it
 * is absent.
 *
 * @author vyrriox
 */
public final class AccessoriesCompat {
    private static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("accessories", "main");
    /** Replies to the local player's own menus: a screen choice, a scroll, the camera's holder settings. */
    private static final Set<String> LIVE_ONLY = Set.of(
            "io.wispforest.accessories.networking.client.ScreenVariantPing",
            "io.wispforest.accessories.networking.server.MenuScroll",
            "io.wispforest.accessories.networking.holder.SyncHolderChange");

    private static Reflect reflect;
    private static boolean reflectFailed;

    private AccessoriesCompat() {
    }

    private record Reflect(Object slotTypes, Method getSlotTypes, Object entitySlots, Method getEntitySlotData,
                           Method setEntitySlotData, Object groups, Method getGroups, Method getUniqueGroups,
                           Method getProperties, Constructor<?> syncDataNew, Method syncTo, Method getCapability,
                           Method getContainers, Method getHolder, Field invalidStacks, Method getAccessories,
                           Method getCosmeticAccessories, Constructor<?> payloadNew, Method payloadMessage,
                           Object packetId) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.ACCESSORIES) {
            return reflect;
        }
        try {
            ClassLoader loader = AccessoriesCompat.class.getClassLoader();
            Class<?> slotTypes = Class.forName("io.wispforest.accessories.data.SlotTypeLoader", false, loader);
            Class<?> entitySlots = Class.forName("io.wispforest.accessories.data.EntitySlotLoader", false, loader);
            Class<?> groups = Class.forName("io.wispforest.accessories.data.SlotGroupLoader", false, loader);
            Class<?> unique = Class.forName("io.wispforest.accessories.api.slot.UniqueSlotHandling", false, loader);
            Class<?> properties = Class.forName("io.wispforest.accessories.api.slot.ExtraSlotTypeProperties", false, loader);
            Class<?> syncData = Class.forName("io.wispforest.accessories.networking.client.SyncData", false, loader);
            Class<?> syncContainer = Class.forName("io.wispforest.accessories.networking.client.SyncEntireContainer", false, loader);
            Class<?> capability = Class.forName("io.wispforest.accessories.api.AccessoriesCapability", false, loader);
            Class<?> container = Class.forName("io.wispforest.accessories.api.AccessoriesContainer", false, loader);
            Class<?> holderImpl = Class.forName("io.wispforest.accessories.impl.AccessoriesHolderImpl", false, loader);
            Class<?> networking = Class.forName("io.wispforest.accessories.networking.AccessoriesNetworking", false, loader);
            Class<?> channel = Class.forName("io.wispforest.owo.network.OwoNetChannel", false, loader);
            Class<?> payload = Class.forName("io.wispforest.owo.network.OwoNetChannel$MessagePayload", false, loader);
            Field packetId = channel.getDeclaredField("packetId");
            packetId.setAccessible(true);
            reflect = new Reflect(slotTypes.getField("INSTANCE").get(null), slotTypes.getMethod("getSlotTypes", boolean.class),
                    entitySlots.getField("INSTANCE").get(null), entitySlots.getMethod("getEntitySlotData", boolean.class),
                    entitySlots.getMethod("setEntitySlotData", java.util.SequencedMap.class),
                    groups.getField("INSTANCE").get(null), groups.getMethod("getGroups", boolean.class, boolean.class),
                    unique.getMethod("getGroups", boolean.class), properties.getMethod("getProperties", boolean.class),
                    syncData.getConstructor(List.class, Map.class, List.class, List.class, Map.class),
                    syncContainer.getMethod("syncTo", LivingEntity.class, Consumer.class),
                    capability.getMethod("get", LivingEntity.class), capability.getMethod("getContainers"),
                    capability.getMethod("getHolder"), holderImpl.getField("invalidStacks"),
                    container.getMethod("getAccessories"), container.getMethod("getCosmeticAccessories"),
                    payload.getConstructor(CustomPacketPayload.Type.class, Record.class), payload.getMethod("message"),
                    packetId.get(networking.getField("CHANNEL").get(null)));
            JustReplay.LOGGER.info("Accessories detected: slot definitions and worn accessories are recorded in snapshots and checkpoints");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("accessories.reflect", "Accessories support disabled, its classes changed", t);
        }
        return reflect;
    }

    private static boolean isAccessories(CustomPacketPayload payload) {
        return CHANNEL.equals(payload.type().id());
    }

    private static Packet<?> wrap(Reflect r, Object message) throws ReflectiveOperationException {
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.payloadNew.newInstance(r.packetId, message));
    }

    /** The slot containers of an entity (slot name to container), or null when it has no capability. */
    private static Map<?, ?> containers(Reflect r, LivingEntity entity) throws ReflectiveOperationException {
        Object capability = r.getCapability.invoke(null, entity);
        return capability == null ? null : (Map<?, ?>) r.getContainers.invoke(capability);
    }

    private static boolean wearsAnything(Reflect r, Map<?, ?> containers) throws ReflectiveOperationException {
        for (Object container : containers.values()) {
            if (!((Container) r.getAccessories.invoke(container)).isEmpty()
                    || !((Container) r.getCosmeticAccessories.invoke(container)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- recording

    /**
     * Writes the client's slot definitions, as {@code SyncData.create()} builds them from the server's maps, which are
     * empty on a client connected to a dedicated server. Nothing when the client has none: an empty definition would
     * wipe what the replay already has.
     */
    static void writeSnapshot(Consumer<Packet<?>> out) throws ReflectiveOperationException {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        Map<?, ?> types = (Map<?, ?>) r.getSlotTypes.invoke(r.slotTypes, true);
        if (types.isEmpty()) {
            return;
        }
        Map<Object, List<Object>> entitySlots = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) r.getEntitySlotData.invoke(r.entitySlots, true)).entrySet()) {
            entitySlots.put(entry.getKey(), List.copyOf(((Map<?, ?>) entry.getValue()).keySet()));
        }
        out.accept(wrap(r, r.syncDataNew.newInstance(List.copyOf(types.values()), entitySlots,
                r.getGroups.invoke(r.groups, true, false), List.copyOf((Set<?>) r.getUniqueGroups.invoke(null, true)),
                r.getProperties.invoke(null, true))));
    }

    /**
     * Writes what an entity wears now (snapshots and checkpoints), as the server sends it. A player is always written,
     * since later changes apply to the containers this creates; a mob only when it wears something.
     */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            Map<?, ?> containers = containers(r, living);
            if (containers == null || containers.isEmpty() || !(entity instanceof Player) && !wearsAnything(r, containers)) {
                return;
            }
            Packet<?>[] packet = new Packet<?>[1];
            Consumer<Record> sink = message -> {
                try {
                    packet[0] = wrap(r, message);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            r.syncTo.invoke(null, living, sink);
            if (packet[0] != null) {
                out.accept(packet[0]);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.snapshot", "Cannot record the Accessories of " + entity, t);
        }
    }

    // ---------------------------------------------------------------- replay

    /** Drops the replies to the local player's own menus; what changes an entity's accessories plays as recorded. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !isAccessories(custom.payload())) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        try {
            return LIVE_ONLY.contains(r.payloadMessage.invoke(custom.payload()).getClass().getName()) ? null : packet;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.filter", "Cannot read an Accessories packet", t);
            return packet;
        }
    }

    /** Requests of the Accessories screen (open, nuke, settings): the replay host has no server for them. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && isAccessories(custom.payload());
    }

    // ---------------------------------------------------------------- self test

    /** Puts a stack in the first place of a slot of an entity, on the thread owning it; false when it has no such slot. */
    public static boolean equip(LivingEntity entity, String slot, ItemStack stack) {
        Reflect r = reflect();
        if (r == null) {
            return false;
        }
        try {
            Map<?, ?> containers = containers(r, entity);
            Object container = containers == null ? null : containers.get(slot);
            if (container == null) {
                return false;
            }
            ((Container) r.getAccessories.invoke(container)).setItem(0, stack);
            return true;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.equip", "Cannot equip an accessory on " + entity, t);
            return false;
        }
    }

    /** The stack in the first place of a slot of an entity; null when it has no capability or no such slot. */
    public static ItemStack worn(Entity entity, String slot) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return null;
        }
        try {
            Map<?, ?> containers = containers(r, living);
            Object container = containers == null ? null : containers.get(slot);
            return container == null ? null : ((Container) r.getAccessories.invoke(container)).getItem(0);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.worn", "Cannot read the Accessories of " + entity, t);
            return null;
        }
    }

    /** Stacks of an entity that fit no slot it has; -1 when it has no capability. */
    public static int invalidCount(Entity entity) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity living)) {
            return -1;
        }
        try {
            Object capability = r.getCapability.invoke(null, living);
            return capability == null ? -1 : ((List<?>) r.invalidStacks.get(r.getHolder.invoke(capability))).size();
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.invalid", "Cannot read the Accessories of " + entity, t);
            return -1;
        }
    }

    /** Empties the client's entity slot definitions, as in a game just started, and returns them for a later restore. */
    public static java.util.SequencedMap<?, ?> clearClientEntitySlots() {
        Reflect r = reflect();
        if (r == null) {
            return null;
        }
        try {
            java.util.SequencedMap<?, ?> saved = new LinkedHashMap<>((Map<?, ?>) r.getEntitySlotData.invoke(r.entitySlots, true));
            r.setEntitySlotData.invoke(r.entitySlots, new LinkedHashMap<>());
            return saved;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.clear", "Cannot clear the Accessories slot definitions", t);
            return null;
        }
    }

    /** Puts back what {@link #clearClientEntitySlots} returned. */
    public static void restoreClientEntitySlots(java.util.SequencedMap<?, ?> saved) {
        Reflect r = reflect();
        if (r == null || saved == null) {
            return;
        }
        try {
            r.setEntitySlotData.invoke(r.entitySlots, saved);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("accessories.restore", "Cannot restore the Accessories slot definitions", t);
        }
    }
}
