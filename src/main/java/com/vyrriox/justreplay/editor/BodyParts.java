package com.vyrriox.justreplay.editor;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;

import java.util.List;

/**
 * Body parts a project can hide on a player actor or a recorded player, as a bit mask: a headless rider, a
 * one-armed pirate. A hidden part takes its skin overlay with it, and a hidden arm its held item.
 *
 * @author vyrriox
 */
public final class BodyParts {
    public static final int HEAD = 1;
    public static final int BODY = 2;
    public static final int LEFT_ARM = 4;
    public static final int RIGHT_ARM = 8;
    public static final int LEFT_LEG = 16;
    public static final int RIGHT_LEG = 32;
    public static final int ALL = 63;

    /** A part with its translation key suffix. */
    public record Part(int bit, String key) {
    }

    /** Every part, in the order the editor shows them. */
    public static final List<Part> PARTS = List.of(new Part(HEAD, "head"), new Part(BODY, "body"),
            new Part(LEFT_ARM, "left_arm"), new Part(RIGHT_ARM, "right_arm"),
            new Part(LEFT_LEG, "left_leg"), new Part(RIGHT_LEG, "right_leg"));

    private BodyParts() {
    }

    /** Parts hidden on this entity by the replay project: 0 for none. */
    public static int hidden(Entity entity) {
        if (ActorManager.isActorEntity(entity)) {
            return ActorManager.hiddenParts(entity);
        }
        if (!entity.level().isClientSide()) {
            return 0;
        }
        ReplayProject.EntityEdit edit = EntityEdits.editOf(entity);
        return edit == null ? 0 : edit.hiddenParts & ALL;
    }

    public static void apply(PlayerModel<?> model, int mask) {
        if ((mask & HEAD) != 0) {
            model.head.visible = false;
            model.hat.visible = false;
        }
        if ((mask & BODY) != 0) {
            model.body.visible = false;
            model.jacket.visible = false;
        }
        if ((mask & LEFT_ARM) != 0) {
            model.leftArm.visible = false;
            model.leftSleeve.visible = false;
        }
        if ((mask & RIGHT_ARM) != 0) {
            model.rightArm.visible = false;
            model.rightSleeve.visible = false;
        }
        if ((mask & LEFT_LEG) != 0) {
            model.leftLeg.visible = false;
            model.leftPants.visible = false;
        }
        if ((mask & RIGHT_LEG) != 0) {
            model.rightLeg.visible = false;
            model.rightPants.visible = false;
        }
    }

    /** True when the arm on this side is hidden: the item it holds goes with it. */
    public static boolean armHidden(Entity entity, HumanoidArm arm) {
        int mask = hidden(entity);
        return mask != 0 && (mask & (arm == HumanoidArm.LEFT ? LEFT_ARM : RIGHT_ARM)) != 0;
    }
}
