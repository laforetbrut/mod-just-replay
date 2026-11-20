package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.editor.ArmorSlots;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Armourer's Workshop wardrobe skins a hidden armor slot takes with it.
 *
 * @author vyrriox
 */
class ArmourersArmorCoverTest {
    @Test
    void eachArmorSlotHidesItsOwnWardrobeSkins() {
        assertTrue(ArmourersArmorCover.hides(ArmorSlots.HEAD, "HEAD"));
        assertTrue(ArmourersArmorCover.hides(ArmorSlots.CHEST, "CHEST"));
        assertTrue(ArmourersArmorCover.hides(ArmorSlots.LEGS, "LEGS"));
        assertTrue(ArmourersArmorCover.hides(ArmorSlots.FEET, "FEET"));
        assertFalse(ArmourersArmorCover.hides(ArmorSlots.HEAD, "CHEST"));
        assertFalse(ArmourersArmorCover.hides(ArmorSlots.LEGS | ArmorSlots.FEET, "HEAD"));
    }

    @Test
    void wingsFollowTheChestLikeAnElytra() {
        assertTrue(ArmourersArmorCover.hides(ArmorSlots.CHEST, "WINGS"));
        assertFalse(ArmourersArmorCover.hides(ArmorSlots.HEAD | ArmorSlots.LEGS | ArmorSlots.FEET, "WINGS"));
    }

    @Test
    void anOutfitGoesWithAnyHiddenSlot() {
        // Constants only: ArmorSlots itself is never loaded, its slot list reaches game classes.
        for (int slot : new int[]{ArmorSlots.HEAD, ArmorSlots.CHEST, ArmorSlots.LEGS, ArmorSlots.FEET}) {
            assertTrue(ArmourersArmorCover.hides(slot, "OUTFIT"), "slot " + slot);
        }
        assertFalse(ArmourersArmorCover.hides(0, "OUTFIT"));
    }

    @Test
    void weaponsToolsAndDyesAreNeverArmor() {
        for (String type : new String[]{"SWORD", "SHIELD", "BOW", "TRIDENT", "PICKAXE", "AXE", "SHOVEL", "HOE",
                "DYE", "HORSE", "DEFAULT", "BACKPACK", "ANY", "", null}) {
            assertEquals(0, ArmourersArmorCover.coveredSlots(type), String.valueOf(type));
            assertFalse(ArmourersArmorCover.hides(ArmorSlots.ALL, type), String.valueOf(type));
        }
    }
}
