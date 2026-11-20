package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.editor.ArmorBits;
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
        assertTrue(ArmourersArmorCover.hides(ArmorBits.HEAD, "HEAD"));
        assertTrue(ArmourersArmorCover.hides(ArmorBits.CHEST, "CHEST"));
        assertTrue(ArmourersArmorCover.hides(ArmorBits.LEGS, "LEGS"));
        assertTrue(ArmourersArmorCover.hides(ArmorBits.FEET, "FEET"));
        assertFalse(ArmourersArmorCover.hides(ArmorBits.HEAD, "CHEST"));
        assertFalse(ArmourersArmorCover.hides(ArmorBits.LEGS | ArmorBits.FEET, "HEAD"));
    }

    @Test
    void wingsFollowTheChestLikeAnElytra() {
        assertTrue(ArmourersArmorCover.hides(ArmorBits.CHEST, "WINGS"));
        assertFalse(ArmourersArmorCover.hides(ArmorBits.HEAD | ArmorBits.LEGS | ArmorBits.FEET, "WINGS"));
    }

    @Test
    void anOutfitGoesWithAnyHiddenSlot() {
        for (int slot : new int[]{ArmorBits.HEAD, ArmorBits.CHEST, ArmorBits.LEGS, ArmorBits.FEET}) {
            assertTrue(ArmourersArmorCover.hides(slot, "OUTFIT"), "slot " + slot);
        }
        assertFalse(ArmourersArmorCover.hides(0, "OUTFIT"));
    }

    @Test
    void weaponsToolsAndDyesAreNeverArmor() {
        for (String type : new String[]{"SWORD", "SHIELD", "BOW", "TRIDENT", "PICKAXE", "AXE", "SHOVEL", "HOE",
                "DYE", "HORSE", "DEFAULT", "BACKPACK", "ANY", "", null}) {
            assertEquals(0, ArmourersArmorCover.coveredSlots(type), String.valueOf(type));
            assertFalse(ArmourersArmorCover.hides(ArmorBits.ALL, type), String.valueOf(type));
        }
    }
}
