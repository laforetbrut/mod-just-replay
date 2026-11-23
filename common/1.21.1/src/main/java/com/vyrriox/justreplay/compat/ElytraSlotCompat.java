package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * Elytra Slot puts an elytra in a slot of its own (the Curios {@code back} slot on NeoForge and Forge, a Trinkets slot on
 * Fabric) and draws it with a render layer of its own, {@code ElytraSlotLayer}, which the vanilla elytra hook of the
 * hidden armor never sees. An optional mixin on that layer skips it while the replay hides the chestplate of the entity
 * drawn, as for a vanilla elytra. The elytra is a worn Curios or Trinkets item: snapshots and checkpoints already carry it
 * on NeoForge ({@link CuriosCompat}).
 *
 * @author vyrriox
 */
public final class ElytraSlotCompat {
    private static int skipped;
    private static boolean announced;

    private ElytraSlotCompat() {
    }

    /** Render hook of the slot elytra: true to skip it, when the running replay hides this entity's chestplate. */
    public static boolean hides(LivingEntity entity) {
        return Integrations.ELYTRA_SLOT.on() && Integrations.ELYTRA_SLOT.test("hides", () -> {
            if (!announced) {
                announced = true;
                JustReplay.LOGGER.info("Elytra Slot detected: an elytra worn in its slot goes with a hidden chestplate");
            }
            if (!ArmorSlots.hides(entity, EquipmentSlot.CHEST)) {
                return false;
            }
            skipped++;
            ArmorSlots.countSkipped();
            return true;
        });
    }

    /** How many times the slot elytra was skipped since the game started (self test). */
    public static int skipped() {
        return skipped;
    }
}
