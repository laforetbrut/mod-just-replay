package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.platform.Platform;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self test of mods that bring worn items of their own and have no state integration of their own: on this version,
 * the elytra of Elytra Slot. It is put on well before the recording starts, so only the recording's snapshot can hand it
 * to the replay. In the replay, at its start and after a forward and a backward seek, the recording player's actor must
 * wear it; hiding the actor's chestplate must skip the slot elytra; the MCP observation then has to list it in its slot.
 * A probe runs only when its mod is installed: a run without it logs nothing here.
 *
 * @author vyrriox
 */
final class SelfTestModState {
    /** An item the recording player wears before the recording: its id, its MCP slot and the command that puts it on. */
    private record WornProbe(String mod, String needs, String item, String slot, String command) {
    }

    private static final List<WornProbe> WORN_PROBES = List.of(
            new WornProbe("elytraslot", "curios", "minecraft:elytra", "curios:back", "curios replace back 0 @s with minecraft:elytra"),
            new WornProbe("elytraslot", "trinkets", "minecraft:elytra", "trinkets:chest/cape", "trinkets set chest cape 0 minecraft:elytra"));

    /** Items of the probes the recording player wore when the recording started. */
    private static final List<String> WORN = new ArrayList<>();
    /** The same, as {@code slot=item}, for the MCP observation. */
    private static final List<String> WORN_SLOTS = new ArrayList<>();
    /** The entity edit hiding the chestplate of the recording player's actor, while {@link #checkHiddenChest} waits. */
    private static com.vyrriox.justreplay.editor.ReplayProject.EntityEdit hiddenChest;
    private static int elytraSkippedBefore;
    private static int frameWaits;
    private static int clearPolls;
    private static net.minecraft.world.phys.Vec3 cameraAt;
    private static float cameraYaw;
    private static float cameraPitch;
    /** The Trinkets slot definitions of the client, emptied by {@link #clearTrinketSlots} until the seek that follows. */
    private static java.util.Map<?, ?> trinketSlots;

    private SelfTestModState() {
    }

    private static void log(String message, Object... args) {
        JustReplay.LOGGER.info("SELFTEST " + message, args);
    }

    private static boolean installed(WornProbe probe) {
        return Platform.get().isModLoaded(probe.mod()) && Platform.get().isModLoaded(probe.needs());
    }

    /** Before the recording, with time to settle: puts on the items of the installed mods. */
    static void prepare(LocalPlayer p) {
        for (WornProbe probe : WORN_PROBES) {
            if (installed(probe)) {
                p.connection.sendCommand(probe.command());
                log("mod state: {} installed, {} put on before the recording", probe.mod(), probe.item());
            }
        }
    }

    /** Just after the recording started: the items of the probes the recording player wears. */
    static void captureStart(Minecraft mc) {
        WORN.clear();
        WORN_SLOTS.clear();
        if (mc.player == null) {
            return;
        }
        Set<String> items = wornItems(mc.player);
        for (WornProbe probe : WORN_PROBES) {
            if (installed(probe)) {
                boolean on = items.contains(probe.slot() + "=" + probe.item());
                log("mod state: {} worn by the recording player in {} when the recording started: {} -> {}", probe.item(),
                        probe.slot(), on, on ? "OK" : "FAIL");
                if (on) {
                    WORN.add(probe.item());
                    WORN_SLOTS.add(probe.slot() + "=" + probe.item());
                }
            }
        }
    }

    /** In the replay: the recording player's actor wears the same items in the same slots. */
    static void check(Minecraft mc, ReplaySession s, String label) {
        if (WORN_SLOTS.isEmpty()) {
            return;
        }
        Entity actor = s.findRecorder(mc);
        Set<String> items = actor == null ? Set.of() : wornItems(actor);
        List<String> missing = WORN_SLOTS.stream().filter(i -> !items.contains(i)).toList();
        log("mod state {}: the recording player's actor wears {}{} -> {}", label, items, missing.isEmpty() ? "" : ", missing " + missing,
                actor != null && missing.isEmpty() ? "OK" : "FAIL");
    }

    /**
     * Paused replay, Elytra Slot installed and its elytra worn: hides the chestplate of the recording player's actor (as
     * the Entities tab does) and puts the camera three blocks behind it, for {@link #checkHiddenChest} some frames later.
     */
    static void hideChest(Minecraft mc, ReplaySession s) {
        if (!Platform.get().isModLoaded("elytraslot") || !WORN.contains("minecraft:elytra") || mc.player == null) {
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
     * True while {@link #checkHiddenChest} must wait: a resource reload covers the screen (a big pack takes a while to
     * reload when a replay opens) and the layers it judges are drawn only once it is gone, then for two seconds of
     * frames. Two minutes at most.
     */
    static boolean waitingForFrames(Minecraft mc) {
        if (hiddenChest == null || frameWaits >= 480) {
            return false;
        }
        frameWaits++;
        if (mc.getOverlay() != null) {
            clearPolls = 0;
            return true;
        }
        return ++clearPolls < 8;
    }

    /** How many of the items the probes put on sit in Curios slots: the ring check counts them with its ring. */
    static int curiosWorn() {
        return (int) WORN_SLOTS.stream().filter(worn -> worn.startsWith("curios:")).count();
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

    /**
     * Trinkets, when a probe put something in one of its slots: empties the slot definitions of the client, as a game
     * just started has them. The backward seek that follows must bring them back from the recording.
     */
    static void clearTrinketSlots() {
        if (WORN_SLOTS.stream().anyMatch(worn -> worn.startsWith("trinkets:"))) {
            trinketSlots = com.vyrriox.justreplay.compat.TrinketsCompat.clearClientSlots();
            log("trinkets: slot definitions of the client emptied before the backward seek, {} entity types left",
                    com.vyrriox.justreplay.compat.TrinketsCompat.clientSlotTypes());
        }
    }

    /** After the backward seek: the slot definitions are back, from the recording alone. */
    static void checkTrinketSlots() {
        if (trinketSlots == null) {
            return;
        }
        int types = com.vyrriox.justreplay.compat.TrinketsCompat.clientSlotTypes();
        log("trinkets after a seek with empty slot definitions: {} entity types defined again -> {}", types,
                types > 0 ? "OK" : "FAIL");
        if (types <= 0) {
            com.vyrriox.justreplay.compat.TrinketsCompat.restoreClientSlots(trinketSlots);
        }
        trinketSlots = null;
    }

    /** Items the MCP observation must list on the recording player, each in its slot ({@code slot=item}). */
    static List<String> mcpWorn() {
        return List.copyOf(WORN_SLOTS);
    }

    /** What an entity wears or holds, as {@code slot=item}: vanilla slots and the slots of mods. */
    private static Set<String> wornItems(Entity entity) {
        Set<String> out = new LinkedHashSet<>();
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    out.add(slot.getName() + "=" + BuiltInRegistries.ITEM.getKey(stack.getItem()));
                }
            }
            com.vyrriox.justreplay.compat.ReplayCompat.describeWorn(living,
                    (slot, stack) -> out.add(slot + "=" + BuiltInRegistries.ITEM.getKey(stack.getItem())));
        }
        return out;
    }
}
