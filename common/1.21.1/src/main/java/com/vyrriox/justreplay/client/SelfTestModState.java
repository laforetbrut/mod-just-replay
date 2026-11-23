package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.Platform;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Self test of mods that bring their own entities or worn items and have no integration of their own: Easy NPC and Man
 * of Many Planes entities, items worn in mod slots (Simple Hats, CosmeticArmours, Elytra Slot) and the Create Jetpack.
 * They are set up well before the recording starts, so only the recording's snapshot can hand them to the replay. In the
 * replay, at its start and after a forward and a backward seek, each entity must be there with every synced value the
 * live client had (byte for byte, the values that did not change while recording), and the recording player's actor
 * must wear the same items. The MCP observation check then has to list them. A probe runs only when its mod is
 * installed: a run without these mods logs nothing here.
 *
 * @author vyrriox
 */
final class SelfTestModState {
    /** An entity of a mod, summoned before the recording at an offset from the player, out of the walking circle. */
    private record EntityProbe(String mod, String type, int dx, int dz, String nbt) {
    }

    /**
     * An item the recording player wears before the recording: its item id, the command that puts it on and the slot the
     * MCP observation must list it in.
     */
    private record WornProbe(String mod, String item, String slot, String command) {
    }

    /** A probe entity found on the live client, with the encoded value of each synced data id it had. */
    private record Tracked(EntityProbe probe, UUID uuid, Map<Integer, byte[]> start, Map<Integer, String> serializers) {
    }

    private static final List<EntityProbe> ENTITY_PROBES = List.of(
            new EntityProbe("easy_npc", "easy_npc:humanoid", 6, 7,
                    "{CustomName:'\"Probe NPC\"',NoAI:1b,PersistenceRequired:1b,SkinData:{Type:\"DEFAULT\",Name:\"probe\"}}"),
            new EntityProbe("man_of_many_planes", "man_of_many_planes:scarlet_biplane", -7, 7, "{}"));

    private static final List<WornProbe> WORN_PROBES = List.of(
            new WornProbe("simplehats", "simplehats:acornhat", "curios:head", "curios replace head 0 @s with simplehats:acornhat"),
            new WornProbe("cosmeticarmoursmod", "cosmeticarmoursmod:fox_tail_orange_chestplate", "curios:tail",
                    "curios replace tail 0 @s with cosmeticarmoursmod:fox_tail_orange_chestplate"),
            new WornProbe("elytraslot", "minecraft:elytra", "curios:back", "curios replace back 0 @s with minecraft:elytra"),
            new WornProbe("create_jetpack", "create_jetpack:jetpack", "chest",
                    "item replace entity @s armor.chest with create_jetpack:jetpack"));

    private static final Set<EntityDataSerializer<?>> VANILLA = vanillaSerializers();
    private static final List<Tracked> TRACKED = new ArrayList<>();
    private static final Map<UUID, Map<Integer, byte[]>> STABLE = new HashMap<>();
    /** Items of the probes the recording player wore when the recording started. */
    private static final List<String> WORN = new ArrayList<>();
    /** The same, as {@code slot=item}, for the MCP observation. */
    private static final List<String> WORN_SLOTS = new ArrayList<>();
    private static BlockPos base;
    private static boolean faced;
    /** The entity edit hiding the chestplate of the recording player's actor, while {@link #checkHiddenChest} waits. */
    private static com.vyrriox.justreplay.editor.ReplayProject.EntityEdit hiddenChest;
    private static int elytraSkippedBefore;
    private static net.minecraft.world.phys.Vec3 cameraAt;
    private static float cameraYaw;
    private static float cameraPitch;

    private SelfTestModState() {
    }

    private static void log(String message, Object... args) {
        JustReplay.LOGGER.info("SELFTEST " + message, args);
    }

    private static boolean loaded(String modId) {
        return Platform.get().isModLoaded(modId);
    }

    /** Before the recording, with time to settle: summons the entities and puts on the items of the installed mods. */
    static void prepare(LocalPlayer p) {
        base = p.blockPosition();
        for (EntityProbe probe : ENTITY_PROBES) {
            if (loaded(probe.mod())) {
                p.connection.sendCommand(String.format("summon %s %d %d %d %s", probe.type(), base.getX() + probe.dx(), base.getY(),
                        base.getZ() + probe.dz(), probe.nbt()));
                log("mod state: {} installed, {} summoned before the recording", probe.mod(), probe.type());
            }
        }
        for (WornProbe probe : WORN_PROBES) {
            if (loaded(probe.mod())) {
                p.connection.sendCommand(probe.command());
                log("mod state: {} installed, {} put on before the recording", probe.mod(), probe.item());
            }
        }
    }

    /** Just after the recording started: the probe entities the client holds, their synced values and the worn items. */
    static void captureStart(Minecraft mc) {
        TRACKED.clear();
        STABLE.clear();
        WORN.clear();
        WORN_SLOTS.clear();
        faced = false;
        if (base == null || mc.level == null || mc.player == null) {
            return;
        }
        for (EntityProbe probe : ENTITY_PROBES) {
            if (!loaded(probe.mod())) {
                continue;
            }
            Entity e = nearest(mc, probe);
            if (e == null) {
                log("mod state: no {} on the client when the recording started -> FAIL", probe.type());
                continue;
            }
            TRACKED.add(new Tracked(probe, e.getUUID(), encode(e), serializers(e)));
        }
        Set<String> items = wornItems(mc.player);
        for (WornProbe probe : WORN_PROBES) {
            if (loaded(probe.mod())) {
                boolean on = items.contains(probe.item());
                log("mod state: {} worn by the recording player when the recording started: {} -> {}", probe.item(), on,
                        on ? "OK" : "FAIL");
                if (on) {
                    WORN.add(probe.item());
                    WORN_SLOTS.add(probe.slot() + "=" + probe.item());
                }
            }
        }
    }

    /** Before the recording stops: keeps the values that did not change while recording, the ones a replay must show. */
    static void captureEnd(Minecraft mc) {
        for (Tracked t : TRACKED) {
            Entity e = byUuid(mc, t.uuid());
            Map<Integer, byte[]> now = e == null ? Map.of() : encode(e);
            Map<Integer, byte[]> kept = new TreeMap<>();
            for (Map.Entry<Integer, byte[]> v : t.start().entrySet()) {
                if (Arrays.equals(v.getValue(), now.get(v.getKey()))) {
                    kept.put(v.getKey(), v.getValue());
                }
            }
            STABLE.put(t.uuid(), kept);
            long modded = kept.keySet().stream().filter(id -> t.serializers().get(id).startsWith("mod")).count();
            log("mod state: {} kept {} synced values while recording ({} with a serializer of a mod), {} changed and are not compared",
                    t.probe().type(), kept.size(), modded, t.start().size() - kept.size());
        }
    }

    /** In the replay: each probe entity with the values it had while recording, and the actor wearing the same items. */
    static void check(Minecraft mc, ReplaySession s, String label) {
        for (Tracked t : TRACKED) {
            Entity e = byUuid(mc, t.uuid());
            Map<Integer, byte[]> expected = STABLE.getOrDefault(t.uuid(), Map.of());
            if (e == null) {
                log("mod state {}: {} missing from the replay -> FAIL", label, t.probe().type());
                continue;
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            Map<Integer, byte[]> now = encode(e);
            List<String> differ = new ArrayList<>();
            for (Map.Entry<Integer, byte[]> v : expected.entrySet()) {
                if (!Arrays.equals(v.getValue(), now.get(v.getKey()))) {
                    differ.add(v.getKey() + " (" + t.serializers().get(v.getKey()) + ")");
                }
            }
            boolean ok = type.equals(t.probe().type()) && !expected.isEmpty() && differ.isEmpty();
            log("mod state {}: {} '{}' with {} of {} synced values as recorded{} -> {}", label, type, e.getName().getString(),
                    expected.size() - differ.size(), expected.size(), differ.isEmpty() ? "" : ", differ: " + differ, ok ? "OK" : "FAIL");
        }
        if (!WORN.isEmpty()) {
            Entity actor = s.findRecorder(mc);
            Set<String> items = actor == null ? Set.of() : wornItems(actor);
            List<String> missing = WORN.stream().filter(i -> !items.contains(i)).toList();
            log("mod state {}: the recording player's actor wears {}{} -> {}", label, items,
                    missing.isEmpty() ? "" : ", missing " + missing, actor != null && missing.isEmpty() ? "OK" : "FAIL");
        }
    }

    /**
     * Paused replay, Elytra Slot installed and its elytra worn: hides the chestplate of the recording player's actor (as
     * the Entities tab does) and puts the camera three blocks behind it, for {@link #checkHiddenChest} some frames later.
     */
    static void hideChest(Minecraft mc, ReplaySession s) {
        if (!loaded("elytraslot") || !WORN.contains("minecraft:elytra") || mc.player == null) {
            return;
        }
        Entity actor = s.findRecorder(mc);
        if (!(actor instanceof LivingEntity living)) {
            log("elytra slot: the recording player's actor is missing -> FAIL");
            return;
        }
        hiddenChest = new com.vyrriox.justreplay.editor.ReplayProject.EntityEdit();
        hiddenChest.uuid = actor.getUUID().toString();
        hiddenChest.hiddenArmor = com.vyrriox.justreplay.editor.ArmorSlots.CHEST;
        s.project().entityEdits.add(hiddenChest);
        elytraSkippedBefore = com.vyrriox.justreplay.compat.ElytraSlotCompat.skipped();
        cameraAt = mc.player.position();
        cameraYaw = mc.player.getYRot();
        cameraPitch = mc.player.getXRot();
        net.minecraft.world.phys.Vec3 back = net.minecraft.world.phys.Vec3.directionFromRotation(0, living.yBodyRot).scale(-3.0);
        mc.player.moveTo(actor.getX() + back.x, actor.getY() + 1.0, actor.getZ() + back.z, mc.player.getYRot(), mc.player.getXRot());
        mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, actor.position().add(0, 1.1, 0));
        log("elytra slot: chestplate of the recording player's actor hidden, camera behind it at {}", mc.player.blockPosition());
    }

    /**
     * After {@link #hideChest}: the slot elytra must have been skipped on the frames drawn since, then the edit and the
     * camera go back. {@code shot} takes the screenshot of the actor's back first.
     */
    static void checkHiddenChest(Minecraft mc, ReplaySession s, Runnable shot) {
        if (hiddenChest == null) {
            return;
        }
        shot.run();
        int skipped = com.vyrriox.justreplay.compat.ElytraSlotCompat.skipped() - elytraSkippedBefore;
        log("elytra slot: elytra of the back slot skipped {} times while the actor's chestplate was hidden -> {}", skipped,
                skipped > 0 ? "OK" : "FAIL");
        s.project().entityEdits.remove(hiddenChest);
        hiddenChest = null;
        if (mc.player != null && cameraAt != null) {
            mc.player.moveTo(cameraAt.x, cameraAt.y, cameraAt.z, cameraYaw, cameraPitch);
        }
    }

    /** Entity types the MCP observation must list, the base ones followed by the probe entities found while recording. */
    static List<String> mcpTypes(List<String> base) {
        List<String> out = new ArrayList<>(base);
        for (Tracked t : TRACKED) {
            if (!out.contains(t.probe().type())) {
                out.add(t.probe().type());
            }
        }
        return out;
    }

    /** Items the MCP observation must list on the recording player, each in its slot ({@code slot=item}). */
    static List<String> mcpWorn() {
        return List.copyOf(WORN_SLOTS);
    }

    /** Turns the replay camera to the first probe entity, for a screenshot; false when there is none. */
    static boolean face(Minecraft mc) {
        for (Tracked t : TRACKED) {
            Entity e = byUuid(mc, t.uuid());
            if (e != null && mc.player != null) {
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.position());
                log("mod state: camera turned to {} at {}", t.probe().type(), e.blockPosition());
                faced = true;
                return true;
            }
        }
        return false;
    }

    /** True once {@link #face} turned the camera: the next screenshot shows a probe entity. */
    static boolean faced() {
        return faced;
    }

    private static Entity nearest(Minecraft mc, EntityProbe probe) {
        double x = base.getX() + probe.dx() + 0.5;
        double z = base.getZ() + probe.dz() + 0.5;
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(probe.type())) {
                double d = e.distanceToSqr(x, e.getY(), z);
                if (d < bestDistance) {
                    best = e;
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    private static Entity byUuid(Minecraft mc, UUID uuid) {
        if (mc.level == null) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(uuid)) {
                return e;
            }
        }
        return null;
    }

    /** Item ids an entity wears or holds: vanilla slots and the slots of mods (Curios, Accessories). */
    private static Set<String> wornItems(Entity entity) {
        Set<String> out = new LinkedHashSet<>();
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    out.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                }
            }
            com.vyrriox.justreplay.compat.ReplayCompat.describeWorn(living,
                    (slot, stack) -> out.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
        }
        return out;
    }

    /** The synced values an entity has away from their defaults, each encoded as the network writes it. */
    private static Map<Integer, byte[]> encode(Entity e) {
        Map<Integer, byte[]> out = new TreeMap<>();
        List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
        if (values == null) {
            return out;
        }
        for (SynchedEntityData.DataValue<?> v : values) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), e.level().registryAccess());
            try {
                write(buf, v);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                out.put(v.id(), bytes);
            } catch (RuntimeException ex) {
                out.put(v.id(), ("unwritable: " + ex).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                buf.release();
            }
        }
        return out;
    }

    private static <T> void write(RegistryFriendlyByteBuf buf, SynchedEntityData.DataValue<T> v) {
        v.serializer().codec().encode(buf, v.value());
    }

    /** Data id to "vanilla" or "mod <network id>", for the log. */
    private static Map<Integer, String> serializers(Entity e) {
        Map<Integer, String> out = new HashMap<>();
        List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
        if (values != null) {
            for (SynchedEntityData.DataValue<?> v : values) {
                out.put(v.id(), VANILLA.contains(v.serializer()) ? "vanilla"
                        : "mod " + EntityDataSerializers.getSerializedId(v.serializer()));
            }
        }
        return out;
    }

    /** The serializers of the game itself: the public constants of {@link EntityDataSerializers}. */
    private static Set<EntityDataSerializer<?>> vanillaSerializers() {
        Set<EntityDataSerializer<?>> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field f : EntityDataSerializers.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && EntityDataSerializer.class.isAssignableFrom(f.getType())) {
                try {
                    out.add((EntityDataSerializer<?>) f.get(null));
                } catch (IllegalAccessException ignored) {
                    // A constant that cannot be read is counted as a mod's: only the log line changes.
                }
            }
        }
        return out;
    }
}
