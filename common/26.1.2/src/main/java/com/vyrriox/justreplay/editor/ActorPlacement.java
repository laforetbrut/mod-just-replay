package com.vyrriox.justreplay.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/**
 * Where an actor goes when it is placed on a block: standing on the ground, sitting on a seat or lying in a bed,
 * and how that is written into the project (a keyframe at the play head, plus the matching action).
 *
 * @author vyrriox
 */
public final class ActorPlacement {
    /** What a placement does. */
    public enum Kind {
        /** Stands the actor there (adds or moves it). */
        PLACE,
        /** Makes the actor walk there from the play head. */
        WALK_TO,
        /** Seats the actor on the block. */
        SIT,
        /** Lays the actor in the bed. */
        BED
    }

    /** How far the placement ray reaches, in blocks. */
    public static final double REACH = 96.0;
    /** Height of a sleeper above its bed block, as vanilla puts a sleeping player. */
    private static final double BED_HEIGHT = 0.6875;
    /** How far a seat on stairs moves towards their low step. */
    private static final double STAIR_SHIFT = 0.2;

    /**
     * A computed spot: feet (or seat) position, facing and pose. {@code block} is the seat or bed used, or null;
     * {@code valid} is false when the spot cannot be used (a bed spot not on a bed, a ray that hit nothing).
     */
    public record Spot(double x, double y, double z, float yaw, ActorAnimator.PreviewPose pose, BlockPos block, boolean valid) {
        public Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    private ActorPlacement() {
    }

    /** First block hit along a ray; OUTLINE also hits blocks without collision (carpets, flowers, furniture). */
    public static BlockHitResult raycast(Vec3 from, Vec3 direction, boolean outline) {
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 to = from.add(direction.normalize().scale(REACH));
        if (level == null) {
            return BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to));
        }
        return level.clip(new ClipContext(from, to, outline ? ClipContext.Block.OUTLINE : ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
    }

    /** Where the camera looks: the main camera position and its view direction. */
    public static BlockHitResult crosshair(boolean outline) {
        net.minecraft.client.Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return raycast(camera.getPosition(), new Vec3(camera.getLookVector()), outline);
    }

    /** Standing spot of a hit: on a top face where it was hit, else just in front of the face, on the ground. */
    public static Spot ground(BlockHitResult hit, float yaw, boolean blockCenter) {
        if (hit.getType() != HitResult.Type.BLOCK) {
            return new Spot(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, yaw, ActorAnimator.PreviewPose.STAND, null, false);
        }
        Vec3 p = hit.getLocation();
        Direction face = hit.getDirection();
        double x = p.x;
        double z = p.z;
        double y = p.y;
        if (face != Direction.UP) {
            x += face.getStepX() * 0.35;
            z += face.getStepZ() * 0.35;
            y = ActorAnimator.groundY(x, face == Direction.DOWN ? p.y - 1.5 : p.y, z);
        }
        if (blockCenter) {
            x = Mth.floor(x) + 0.5;
            z = Mth.floor(z) + 0.5;
            y = ActorAnimator.groundY(x, y + 0.5, z);
        }
        return new Spot(x, y, z, yaw, ActorAnimator.PreviewPose.STAND, null, true);
    }

    /**
     * Seat on the hit block: the top of its collision shape above the seat point (slabs, carpets, mod chairs),
     * the top surface of a full block. Stairs seat on their low step, facing away from their back, turned by
     * {@code offset}; other seats face {@code yaw}.
     */
    public static Spot seat(BlockHitResult hit, float yaw, float offset) {
        ClientLevel level = Minecraft.getInstance().level;
        if (hit.getType() != HitResult.Type.BLOCK || level == null) {
            return new Spot(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, yaw + offset, ActorAnimator.PreviewPose.SIT, null, false);
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        float facing = yaw + offset;
        if (state.getBlock() instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.BOTTOM) {
            Direction front = state.getValue(StairBlock.FACING).getOpposite();
            x += front.getStepX() * STAIR_SHIFT;
            z += front.getStepZ() * STAIR_SHIFT;
            facing = front.toYRot() + offset;
        }
        return new Spot(x, seatHeight(level, pos, state, x, z), z, facing, ActorAnimator.PreviewPose.SIT, pos, true);
    }

    /** Top of the collision shape of a block above a point, or the ground under it for blocks without collision. */
    static double seatHeight(ClientLevel level, BlockPos pos, BlockState state, double x, double z) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return ActorAnimator.groundY(x, pos.getY(), z);
        }
        BlockHitResult top = shape.clip(new Vec3(x, pos.getY() + 1.001, z), new Vec3(x, pos.getY() - 0.001, z), pos);
        return top != null ? top.getLocation().y : pos.getY() + shape.max(Direction.Axis.Y);
    }

    /**
     * Lying spot in the bed that was hit: like a vanilla sleeper, the entity is on the head block and faces the
     * pillow; a sleeping actor is drawn lying towards where it faces (see the bed orientation mixin).
     */
    public static Spot bed(BlockHitResult hit, Entity entity) {
        ClientLevel level = Minecraft.getInstance().level;
        if (hit.getType() != HitResult.Type.BLOCK || level == null
                || !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock)) {
            return new Spot(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, 0.0F, ActorAnimator.PreviewPose.SLEEP, null, false);
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? hit.getBlockPos() : hit.getBlockPos().relative(facing);
        return new Spot(head.getX() + 0.5, head.getY() + BED_HEIGHT, head.getZ() + 0.5, facing.toYRot(),
                ActorAnimator.PreviewPose.SLEEP, head, true);
    }

    /** Yaw that turns something at {@code from} to face {@code to}. */
    public static float yawTowards(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(-(to.x - from.x), to.z - from.z) * Mth.RAD_TO_DEG);
    }

    // ---------------------------------------------------------------- writing

    /** Adds a keyframe at {@code tick}, replacing the one already there. */
    public static ReplayProject.ActorKeyframe keyframe(ReplayProject.ActorData actor, int tick, double x, double y, double z, float yaw) {
        actor.keyframes.removeIf(k -> k.tick == tick);
        ReplayProject.ActorKeyframe key = new ReplayProject.ActorKeyframe(tick, x, y, z, Mth.wrapDegrees(yaw), 0.0F);
        actor.keyframes.add(key);
        actor.sort();
        return key;
    }

    /** Adds an action at {@code tick}, replacing actions of the same types there. */
    public static ReplayProject.ActorAction action(ReplayProject.ActorData actor, int tick, ActionType type, int duration, String param,
                                                   ActionType... replaces) {
        actor.actions.removeIf(a -> {
            if (a.tick != tick) {
                return false;
            }
            ActionType existing = a.actionType();
            if (existing == type) {
                return true;
            }
            for (ActionType r : replaces) {
                if (existing == r) {
                    return true;
                }
            }
            return false;
        });
        ReplayProject.ActorAction action = new ReplayProject.ActorAction(tick, type, duration, param == null ? "" : param);
        actor.actions.add(action);
        actor.sort();
        return action;
    }

    /** Writes a placement at {@code tick}: the keyframe, and the walk, sit or sleep action it implies. */
    public static void apply(ReplayProject.ActorData actor, Kind kind, Spot spot, int tick) {
        switch (kind) {
            case WALK_TO -> action(actor, tick, ActionType.WALK_TO, 0,
                    String.format(Locale.ROOT, "%.2f %.2f %.2f", spot.x(), spot.y(), spot.z()), ActionType.RUN_TO);
            case SIT -> {
                keyframe(actor, tick, spot.x(), spot.y(), spot.z(), spot.yaw());
                action(actor, tick, ActionType.SIT, 0, "", ActionType.SLEEP, ActionType.IDLE);
            }
            case BED -> {
                keyframe(actor, tick, spot.x(), spot.y(), spot.z(), spot.yaw());
                action(actor, tick, ActionType.SLEEP, 0, "", ActionType.SIT, ActionType.IDLE);
            }
            default -> keyframe(actor, tick, spot.x(), spot.y(), spot.z(), spot.yaw());
        }
    }

    /** Where an actor stands at {@code tick} from its keyframes and moves, else where its entity is; null when unknown. */
    public static double[] stateAt(ReplayProject.ActorData actor, int tick, Entity entity) {
        double[] state = ActorAnimator.placedState(actor, tick, null);
        if (state != null) {
            return state;
        }
        return entity == null ? null : new double[]{entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot()};
    }
}
