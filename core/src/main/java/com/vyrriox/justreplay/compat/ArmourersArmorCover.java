package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.editor.ArmorBits;

/**
 * Which armor slots of a replay each Armourer's Workshop wardrobe slot follows, by the name of its slot type
 * ({@code SkinSlotType}): a hidden slot hides every wardrobe skin that covers it. Head, chest, legs and feet follow
 * their own slot, wings follow the chest like an elytra, and an outfit, drawn as one piece over the whole body, goes
 * as soon as one of the four slots is hidden. Weapons, tools, shields, backpacks, horse skins and dyes are never
 * armor. Plain Java: the slot type names are the only thing read from Armourer's Workshop.
 *
 * @author vyrriox
 */
public final class ArmourersArmorCover {
    private ArmourersArmorCover() {
    }

    /** Armor slots ({@link ArmorBits}) a wardrobe slot type covers, 0 for one that is not armor. */
    public static int coveredSlots(String slotType) {
        if (slotType == null) {
            return 0;
        }
        return switch (slotType) {
            case "HEAD" -> ArmorBits.HEAD;
            case "CHEST", "WINGS" -> ArmorBits.CHEST;
            case "LEGS" -> ArmorBits.LEGS;
            case "FEET" -> ArmorBits.FEET;
            case "OUTFIT" -> ArmorBits.ALL;
            default -> 0;
        };
    }

    /** True when a skin of this wardrobe slot type is hidden on an entity whose hidden slots are {@code hiddenSlots}. */
    public static boolean hides(int hiddenSlots, String slotType) {
        return (hiddenSlots & coveredSlots(slotType)) != 0;
    }
}
