package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Armor a replay project can hide on an actor or a recorded player, as a bit mask over the four armor slots: a knight
 * taking his helmet off for a close-up, or every player back in plain skins. A hidden helmet takes whatever is worn on
 * the head with it (pumpkin, skull), a hidden chestplate the elytra. Only the replay is drawn differently: the entity
 * keeps its equipment, and nothing changes outside a running replay.
 *
 * @author vyrriox
 */
public final class ArmorSlots {
    public static final int HEAD = ArmorBits.HEAD;
    public static final int CHEST = ArmorBits.CHEST;
    public static final int LEGS = ArmorBits.LEGS;
    public static final int FEET = ArmorBits.FEET;
    public static final int ALL = ArmorBits.ALL;

    /** A slot with its translation key suffix. */
    public record Slot(int bit, String key) {
        /** Switch tooltip for the slots that hide more than their armor piece, or null. */
        public net.minecraft.network.chat.Component tip() {
            return bit == HEAD || bit == CHEST ? net.minecraft.network.chat.Component.translatable("justreplay.armor." + key + ".tip") : null;
        }
    }

    /** Every slot, in the order the editor shows them. */
    public static final List<Slot> SLOTS = List.of(new Slot(HEAD, "head"), new Slot(CHEST, "chest"),
            new Slot(LEGS, "legs"), new Slot(FEET, "feet"));

    /** Armor pieces, elytra and head items skipped, for the self test. */
    private static int skipped;
    /** Of those, the pieces the armor layer itself skipped. */
    private static int armorLayerSkipped;

    private ArmorSlots() {
    }

    /** Bit of an equipment slot in the mask: 0 for the hands and the body slot. */
    public static int bit(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> HEAD;
            case CHEST -> CHEST;
            case LEGS -> LEGS;
            case FEET -> FEET;
            default -> 0;
        };
    }

    /**
     * Armor slots hidden on this entity in the running replay: always 0 outside a replay. Called for every humanoid
     * drawn, so the common case (no replay) returns before any lookup.
     */
    public static int hidden(Entity entity) {
        if (!ReplaySession.isRunning()) {
            return 0;
        }
        boolean allPlayers = entity instanceof Player && JRConfig.HIDE_PLAYER_ARMOR.get();
        if (ActorManager.isActorEntity(entity)) {
            return allPlayers ? ALL : ActorManager.hiddenArmor(entity);
        }
        if (!entity.level().isClientSide() || entity == Minecraft.getInstance().player) {
            return 0;
        }
        if (allPlayers) {
            return ALL;
        }
        ReplayProject.EntityEdit edit = EntityEdits.editOf(entity);
        return edit == null ? 0 : edit.hiddenArmor & ALL;
    }

    /** True when the replay hides what this entity wears in this slot. */
    public static boolean hides(Entity entity, EquipmentSlot slot) {
        int bit = bit(slot);
        return bit != 0 && (hidden(entity) & bit) != 0;
    }

    /** Called by the render hooks each time they skip a piece. */
    public static void countSkipped() {
        skipped++;
    }

    public static int skipped() {
        return skipped;
    }

    /** Called by the armor layer hooks each time they skip an armor piece. */
    public static void countArmorLayerSkipped() {
        skipped++;
        armorLayerSkipped++;
    }

    public static int armorLayerSkipped() {
        return armorLayerSkipped;
    }
}
