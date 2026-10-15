package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.mixin.EntityInvoker;
import com.vyrriox.justreplay.mixin.HoglinAccessor;
import com.vyrriox.justreplay.mixin.IronGolemAccessor;
import com.vyrriox.justreplay.mixin.LivingEntityAccessor;
import com.vyrriox.justreplay.mixin.RavagerAccessor;
import com.vyrriox.justreplay.mixin.WalkAnimationStateAccessor;
import com.vyrriox.justreplay.mixin.ZoglinAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the keyframes and actions of an actor into the animation state of its entity. Everything is derived
 * from the play head, so seeking shows the same pose as playing up to that tick. Vanilla client paths do the
 * drawing: swing and hurt fields, death time, poses, shared flags, item use flags and entity events.
 *
 * @author vyrriox
 */
public final class ActorAnimator {
    public enum Gesture { WAVE, POINT, CHEER, HANDS_UP }

    public enum DanceStyle { BOUNCE, PUMP, HANDS, WAVE, SHUFFLE, HEADBANG, JUMP, DJ }

    /**
     * A dance on a beat grid: {@code origin} is the replay tick of a beat. {@code energy} and {@code lag} vary per
     * actor so a crowd does not move as one body.
     */
    public record Dance(DanceStyle style, double bpm, double origin, float energy, float lag) {
        /** Beats since the origin at a replay time in ticks. */
        public double beats(double time) {
            return (time - origin) / 20.0 * bpm / 60.0 + lag;
        }

        static Dance parse(String param, String seed) {
            String[] parts = (param == null ? "" : param).split("\\|");
            DanceStyle style;
            try {
                style = DanceStyle.valueOf(parts[0].trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                style = DanceStyle.BOUNCE;
            }
            double bpm = parts.length > 1 ? parseDouble(parts[1], 128.0) : 128.0;
            double origin = parts.length > 2 ? parseDouble(parts[2], 0.0) : 0.0;
            int hash = seed.hashCode();
            float energy = 0.75F + ((hash & 0xFF) / 255.0F) * 0.45F;
            float lag = (((hash >> 8) & 0xFF) / 255.0F - 0.5F) * 0.08F;
            return new Dance(style, Mth.clamp(bpm, 20.0, 300.0), origin, energy, lag);
        }

        /** Height of a JUMP dancer above the ground: one hop every two beats, landing on the beat. */
        double hop(double time) {
            double phase = beats(time) / 2.0;
            double f = phase - Math.floor(phase);
            return 0.42 * energy * Math.sin(Math.PI * f);
        }
    }

    /** Feet height offsets of a vanilla jump (0.42 initial speed, 0.08 gravity, 0.98 drag), one per tick. */
    private static final double[] JUMP;
    private static final int SWING_TICKS = 6;
    private static final int HURT_TICKS = 10;
    private static final int ATTACK_TICKS = 10;
    private static final int AGGRESSIVE_TICKS = 20;
    /** Lowers a sitting humanoid so its legs rest on the keyframed ground. */
    private static final double SIT_OFFSET = 0.625;

    static {
        double[] heights = new double[32];
        double y = 0;
        double vy = 0.42;
        int n = 0;
        while (n < heights.length) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            if (y <= 0) {
                break;
            }
            heights[n++] = y;
        }
        JUMP = java.util.Arrays.copyOf(heights, n);
    }

    /** Render side state, read by the humanoid model mixin and the model renderer. Keyed by entity id. */
    private static final Map<Integer, Gesture> GESTURES = new HashMap<>();
    private static final Map<Integer, Dance> DANCES = new HashMap<>();
    private static final java.util.Set<Integer> SITTING = new java.util.HashSet<>();

    private final Map<String, State> states = new HashMap<>();

    /** Per actor memory that cannot be derived from one tick: the walk cycle phase and the last play head. */
    private static final class State {
        float walkSpeed;
        float walkPosition;
        int lastCursor = Integer.MIN_VALUE;
        boolean fallFlying;
        double[] anchor;
        double[] follow;
        int lastFollowCursor = Integer.MIN_VALUE;
    }

    /** Everything an actor shows at one tick. */
    public static final class Frame {
        public double x, y, z;
        public float yaw, pitch, headYaw;
        Pose pose = Pose.STANDING;
        boolean crouch, sit, sprint, aggressive, spin, fallFlying;
        int deathTime, hurtTime, fallFlyTicks = -1, attackCounter;
        float attackAnim, swimAmount;
        int swingTime = -1;
        InteractionHand swingHand = InteractionHand.MAIN_HAND;
        float forcedWalk;
        ActionType use;
        String useParam = "";
        int useElapsed;
        Gesture gesture;
        Dance dance;
    }

    // ---------------------------------------------------------------- render side

    public static boolean isSitting(Entity entity) {
        return ActorManager.isActorEntity(entity) && SITTING.contains(entity.getId());
    }

    /** Arm gestures for humanoid models, after vanilla posed them. */
    public static void applyGesture(Entity entity, float ageInTicks, ModelPart rightArm, ModelPart leftArm) {
        if (!ActorManager.isActorEntity(entity)) {
            return;
        }
        Gesture gesture = GESTURES.get(entity.getId());
        if (gesture == null || DANCES.containsKey(entity.getId())) {
            // A dancer gets its gesture after the dance poses the arms.
            return;
        }
        applyGestureArms(gesture, ageInTicks, rightArm, leftArm);
    }

    private static void applyGestureArms(Gesture gesture, float ageInTicks, ModelPart rightArm, ModelPart leftArm) {
        switch (gesture) {
            case WAVE -> {
                rightArm.xRot = 0.0F;
                rightArm.yRot = 0.0F;
                rightArm.zRot = 2.6F + 0.35F * Mth.sin(ageInTicks * 0.6F);
            }
            case POINT -> {
                rightArm.xRot = (float) (-Math.PI / 2.0);
                rightArm.yRot = -0.1F;
                rightArm.zRot = 0.0F;
            }
            case CHEER -> {
                float bounce = 0.15F * Mth.sin(ageInTicks * 0.5F);
                rightArm.xRot = 0.0F;
                rightArm.yRot = 0.0F;
                rightArm.zRot = 2.7F + bounce;
                leftArm.xRot = 0.0F;
                leftArm.yRot = 0.0F;
                leftArm.zRot = -2.7F - bounce;
            }
            case HANDS_UP -> {
                rightArm.xRot = (float) -Math.PI;
                rightArm.yRot = 0.0F;
                rightArm.zRot = 0.25F;
                leftArm.xRot = (float) -Math.PI;
                leftArm.yRot = 0.0F;
                leftArm.zRot = -0.25F;
            }
        }
    }

    /**
     * Dance of a humanoid actor, after vanilla posed it: the beat phase comes from the replay time of the frame, so
     * the motion is smooth between ticks and stays on the beat in exports. Arm gestures still win over the arms.
     */
    public static void applyDance(Entity entity, net.minecraft.client.model.HumanoidModel<?> model) {
        if (!ActorManager.isActorEntity(entity)) {
            return;
        }
        Dance dance = DANCES.get(entity.getId());
        com.vyrriox.justreplay.replay.ReplaySession session = com.vyrriox.justreplay.replay.ReplaySession.current();
        if (dance == null || session == null) {
            return;
        }
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        double beats = dance.beats(CameraController.frameTime(session, partial));
        float p = (float) (beats - Math.floor(beats));
        float e = dance.energy();
        // 1 on the beat, 0 half way: the knees dip on the beat.
        float dip = 0.5F + 0.5F * Mth.cos(p * Mth.TWO_PI);
        float sway = Mth.sin((float) (beats * Math.PI));
        float twoBeat = Mth.sin((float) (beats * Math.PI * 0.5));
        float drop = 0.0F;
        ModelPart rightArm = model.rightArm;
        ModelPart leftArm = model.leftArm;
        switch (dance.style()) {
            case BOUNCE -> {
                drop = 1.6F * dip * e;
                rightArm.xRot = -0.55F - 0.45F * sway * e;
                leftArm.xRot = -0.55F + 0.45F * sway * e;
                rightArm.zRot = 0.15F;
                leftArm.zRot = -0.15F;
                model.head.xRot += 0.18F * dip * e;
            }
            case PUMP -> {
                drop = 1.3F * dip * e;
                rightArm.xRot = -2.55F + 0.75F * dip * e;
                rightArm.yRot = -0.15F;
                rightArm.zRot = 0.1F;
                leftArm.xRot = -0.35F - 0.25F * sway;
                leftArm.zRot = -0.1F;
                model.head.xRot += 0.22F * dip * e;
            }
            case HANDS -> {
                drop = 0.9F * dip * e;
                rightArm.xRot = -2.95F;
                leftArm.xRot = -2.95F;
                rightArm.yRot = 0.0F;
                leftArm.yRot = 0.0F;
                rightArm.zRot = 0.3F + 0.3F * twoBeat * e;
                leftArm.zRot = -0.3F + 0.3F * twoBeat * e;
            }
            case WAVE -> {
                drop = 0.6F * dip * e;
                rightArm.xRot = 0.0F;
                leftArm.xRot = 0.0F;
                rightArm.zRot = 2.5F + 0.45F * twoBeat * e;
                leftArm.zRot = -2.5F + 0.45F * twoBeat * e;
                model.body.yRot = 0.12F * twoBeat * e;
            }
            case SHUFFLE -> {
                drop = 1.0F * dip * e;
                model.rightLeg.xRot = 0.65F * sway * e;
                model.leftLeg.xRot = -0.65F * sway * e;
                model.rightLeg.zRot = 0.08F + 0.12F * dip;
                model.leftLeg.zRot = -0.08F - 0.12F * dip;
                rightArm.xRot = -0.9F - 0.5F * sway * e;
                leftArm.xRot = -0.9F + 0.5F * sway * e;
            }
            case HEADBANG -> {
                drop = 1.2F * dip * e;
                model.head.xRot = 0.15F + 0.75F * dip * e;
                model.body.xRot = 0.18F * dip * e;
                rightArm.xRot = -1.1F + 0.35F * dip;
                leftArm.xRot = -1.1F + 0.35F * dip;
                rightArm.zRot = 0.35F;
                leftArm.zRot = -0.35F;
            }
            case JUMP -> {
                // The body hops in the frame; the arms go up and punch on the landing.
                rightArm.xRot = -2.8F + 0.4F * dip * e;
                leftArm.xRot = -2.8F + 0.4F * dip * e;
                rightArm.zRot = 0.25F;
                leftArm.zRot = -0.25F;
                model.rightLeg.xRot = 0.25F * dip;
                model.leftLeg.xRot = 0.25F * dip;
            }
            case DJ -> {
                drop = 0.8F * dip * e;
                model.head.xRot = 0.1F + 0.45F * dip * e;
                // One hand on the headphones, the other on the decks, pumping every other eight beats.
                rightArm.xRot = -2.6F;
                rightArm.yRot = -0.55F;
                rightArm.zRot = 0.0F;
                boolean pump = ((long) Math.floor(beats / 8.0)) % 2 == 1;
                leftArm.xRot = pump ? -2.6F + 0.7F * dip : -1.15F + 0.15F * sway;
                leftArm.yRot = pump ? 0.1F : 0.3F;
                leftArm.zRot = 0.0F;
                model.body.yRot = 0.1F * sway;
            }
        }
        if (drop != 0.0F) {
            model.head.y += drop;
            model.body.y += drop;
            rightArm.y += drop;
            leftArm.y += drop;
        }
        model.hat.copyFrom(model.head);
        Gesture gesture = GESTURES.get(entity.getId());
        if (gesture != null) {
            applyGestureArms(gesture, entity.tickCount + partial, rightArm, leftArm);
        }
    }

    public static void forget(Entity entity) {
        if (entity != null) {
            DANCES.remove(entity.getId());
            GESTURES.remove(entity.getId());
            SITTING.remove(entity.getId());
        }
    }

    void forget(ReplayProject.ActorData actor) {
        states.remove(actor.id);
    }

    public static void clearAllRenderState() {
        DANCES.clear();
        GESTURES.clear();
        SITTING.clear();
    }

    // ---------------------------------------------------------------- placement preview

    /** Pose shown by a placement preview. KEEP leaves the pose the actions give at the play head. */
    public enum PreviewPose { KEEP, STAND, SIT, SLEEP }

    /**
     * Shows an actor entity at a placement preview: position, facing and pose, with no motion to interpolate.
     * {@code y} is the ground (or seat) height, like a keyframe.
     */
    public static void showPreview(Entity entity, double x, double y, double z, float yaw, PreviewPose pose) {
        boolean sit = pose == PreviewPose.SIT || pose == PreviewPose.KEEP && isSitting(entity);
        if (sit && isHumanoid(entity)) {
            y -= SIT_OFFSET;
        }
        if (pose != PreviewPose.KEEP) {
            entity.setPose(sit ? Pose.SITTING : pose == PreviewPose.SLEEP ? Pose.SLEEPING : Pose.STANDING);
            if (sit && isHumanoid(entity)) {
                SITTING.add(entity.getId());
            } else {
                SITTING.remove(entity.getId());
            }
            if (entity instanceof TamableAnimal tamable) {
                tamable.setInSittingPose(sit);
            }
        }
        entity.setPos(x, y, z);
        entity.xo = entity.xOld = x;
        entity.yo = entity.yOld = y;
        entity.zo = entity.zOld = z;
        entity.setYRot(yaw);
        entity.yRotO = yaw;
        entity.setXRot(0.0F);
        entity.xRotO = 0.0F;
        entity.setDeltaMovement(Vec3.ZERO);
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = living.yBodyRotO = yaw;
            living.yHeadRot = living.yHeadRotO = yaw;
        }
    }

    // ---------------------------------------------------------------- actions

    /** Ticks since the latest action of these types started at or before {@code t}, or -1. */
    static int elapsedSince(List<ReplayProject.ActorAction> actions, int t, ActionType... types) {
        int best = -1;
        for (ReplayProject.ActorAction action : actions) {
            if (action.tick > t) {
                break;
            }
            ActionType type = action.actionType();
            for (ActionType candidate : types) {
                if (type == candidate) {
                    best = t - action.tick;
                }
            }
        }
        return best;
    }

    /**
     * The latest started state action among {@code types} still active at {@code t}, or null. An {@code IDLE}
     * ends every state started before it; when the latest state has run out, an earlier open ended one shows.
     */
    public static ReplayProject.ActorAction activeState(List<ReplayProject.ActorAction> actions, int t, ActionType... types) {
        java.util.ArrayList<ReplayProject.ActorAction> started = new java.util.ArrayList<>();
        for (ReplayProject.ActorAction action : actions) {
            if (action.tick > t) {
                break;
            }
            ActionType type = action.actionType();
            if (type == ActionType.IDLE) {
                started.clear();
                continue;
            }
            for (ActionType candidate : types) {
                if (type == candidate) {
                    started.add(action);
                    break;
                }
            }
        }
        for (int i = started.size() - 1; i >= 0; i--) {
            ReplayProject.ActorAction action = started.get(i);
            if (action.duration <= 0 || t < action.tick + action.duration) {
                return action;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- frames

    /**
     * Pose of an actor at tick {@code t}. {@code fallback} gives the position when the actor has no keyframe.
     */
    public static Frame frame(ReplayProject.ActorData actor, ReplayProject project, int t, Entity entity, double[] fallback) {
        Frame f = new Frame();
        double[] base = keyframeBase(actor, t);
        if (base == null) {
            base = fallback;
        }
        f.x = base[0];
        f.y = base[1];
        f.z = base[2];
        f.yaw = (float) base[3];
        f.pitch = (float) base[4];
        List<ReplayProject.ActorAction> actions = actor.actions;

        ReplayProject.ActorAction wander = activeState(actions, t, ActionType.WANDER);
        if (wander != null) {
            double radius = parseDouble(wander.param, 2.0);
            double seed = (actor.id.hashCode() & 0xFFFF) / 997.0;
            double[] now = wanderOffset(t, radius, seed);
            double[] next = wanderOffset(t + 1, radius, seed);
            f.x += now[0];
            f.z += now[1];
            double dx = next[0] - now[0];
            double dz = next[1] - now[1];
            if (dx * dx + dz * dz > 1.0E-6) {
                f.yaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
            }
        }
        applyMoves(f, actor, t, base);
        f.headYaw = f.yaw;
        if (shouldSnap(actor, entity, actions, t)) {
            f.y = groundY(f.x, f.y, f.z);
        }

        ReplayProject.ActorAction pose = activeState(actions, t, ActionType.DEATH, ActionType.SLEEP, ActionType.SWIM,
                ActionType.ELYTRA, ActionType.SPIN_ATTACK, ActionType.SIT, ActionType.CROUCH);
        if (pose == null) {
            f.pose = parsePose(actor.pose);
            f.crouch = f.pose == Pose.CROUCHING;
            f.sit = f.pose == Pose.SITTING;
            if (f.pose == Pose.FALL_FLYING) {
                f.fallFlying = true;
                f.fallFlyTicks = 20;
            }
            if (f.pose == Pose.SPIN_ATTACK) {
                f.spin = true;
            }
            if (f.pose == Pose.SWIMMING) {
                f.swimAmount = 1.0F;
            }
        } else {
            int e = t - pose.tick;
            switch (pose.actionType()) {
                case DEATH -> f.deathTime = Math.min(20, e + 1);
                case SLEEP -> f.pose = Pose.SLEEPING;
                case SWIM -> {
                    f.pose = Pose.SWIMMING;
                    f.swimAmount = Math.min(1.0F, (e + 1) * 0.09F);
                }
                case ELYTRA -> {
                    f.pose = Pose.FALL_FLYING;
                    f.fallFlying = true;
                    f.fallFlyTicks = e;
                }
                case SPIN_ATTACK -> {
                    f.pose = Pose.SPIN_ATTACK;
                    f.spin = true;
                }
                case SIT -> {
                    f.pose = Pose.SITTING;
                    f.sit = true;
                }
                case CROUCH -> {
                    f.pose = Pose.CROUCHING;
                    f.crouch = true;
                }
                default -> {
                }
            }
        }
        if (f.sit && isHumanoid(entity)) {
            f.y -= SIT_OFFSET;
        }

        ReplayProject.ActorAction dance = activeState(actions, t, ActionType.DANCE);
        if (dance != null && isHumanoid(entity)) {
            f.dance = Dance.parse(dance.param, actor.id);
            if (f.dance.style() == DanceStyle.JUMP && !f.sit && f.pose == Pose.STANDING) {
                f.y += f.dance.hop(t);
            }
        }

        int jump = elapsedSince(actions, t, ActionType.JUMP);
        if (jump >= 0 && jump < JUMP.length) {
            f.y += JUMP[jump];
        }

        ReplayProject.ActorAction move = activeState(actions, t, ActionType.WALK, ActionType.RUN);
        if (move != null) {
            boolean run = move.actionType() == ActionType.RUN;
            f.forcedWalk = run ? 1.0F : 0.7F;
            f.sprint = run;
        }

        int swing = elapsedSince(actions, t, ActionType.SWING_MAIN, ActionType.SWING_OFF, ActionType.ATTACK);
        if (swing >= 0 && swing < SWING_TICKS) {
            f.swingTime = swing;
            f.attackAnim = (float) swing / SWING_TICKS;
            f.swingHand = elapsedSince(actions, t, ActionType.SWING_OFF) == swing ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }
        int attack = elapsedSince(actions, t, ActionType.ATTACK);
        if (attack >= 0 && attack < ATTACK_TICKS) {
            f.attackCounter = ATTACK_TICKS - attack;
        }
        if (attack >= 0 && attack < AGGRESSIVE_TICKS) {
            f.aggressive = true;
        }
        if (activeState(actions, t, ActionType.AGGRESSIVE) != null) {
            f.aggressive = true;
        }

        int hurt = elapsedSince(actions, t, ActionType.HURT);
        if (hurt >= 0 && hurt < HURT_TICKS) {
            f.hurtTime = HURT_TICKS - hurt;
        }

        ReplayProject.ActorAction use = activeState(actions, t, ActionType.USE_ITEM, ActionType.BLOCK, ActionType.BOW, ActionType.EAT);
        if (use != null) {
            f.use = use.actionType();
            f.useParam = use.param == null ? "" : use.param;
            f.useElapsed = t - use.tick;
            if (f.use == ActionType.BOW) {
                f.aggressive = true;
            }
        }

        ReplayProject.ActorAction gesture = activeState(actions, t, ActionType.WAVE, ActionType.POINT, ActionType.CHEER, ActionType.HANDS_UP);
        if (gesture != null) {
            f.gesture = Gesture.valueOf(gesture.actionType().name());
        }

        ReplayProject.ActorAction look = activeState(actions, t, ActionType.LOOK_AT, ActionType.LOOK_AROUND);
        if (look != null) {
            if (look.actionType() == ActionType.LOOK_AROUND) {
                double phase = (actor.id.hashCode() & 0xFF) / 41.0;
                f.headYaw = f.yaw + (float) (55.0 * Math.sin(t * 0.045 + phase) * Math.sin(t * 0.017 + phase * 0.5));
                f.pitch = (float) (12.0 * Math.sin(t * 0.031 + phase));
            } else {
                Vec3 target = lookTarget(look.param, project, actor, t);
                if (target != null) {
                    double eye = entity != null ? entity.getEyeHeight() : 1.62;
                    double dx = target.x - f.x;
                    double dy = target.y - (f.y + eye);
                    double dz = target.z - f.z;
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    float wanted = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
                    float relative = Mth.clamp(Mth.wrapDegrees(wanted - f.yaw), -75.0F, 75.0F);
                    f.headYaw = f.yaw + relative;
                    f.pitch = Mth.clamp((float) (-Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG), -80.0F, 80.0F);
                }
            }
        }
        return f;
    }

    // ---------------------------------------------------------------- movement

    /** Blocks per tick: vanilla walking and sprinting speeds of a player. */
    private static final double WALK_SPEED = 4.317 / 20.0;
    private static final double RUN_SPEED = 5.612 / 20.0;

    /** Tick of the last keyframe at or before {@code t}, or {@link Integer#MIN_VALUE} when there is none. */
    static int lastKeyframeTick(ReplayProject.ActorData actor, int t) {
        int last = Integer.MIN_VALUE;
        for (ReplayProject.ActorKeyframe key : actor.keyframes) {
            if (key.tick <= t && key.tick > last) {
                last = key.tick;
            }
        }
        return last;
    }

    private static boolean isMove(ActionType type) {
        return type == ActionType.WALK_TO || type == ActionType.RUN_TO;
    }

    /**
     * Keyframed position under the moves at {@code t}. A keyframe is an absolute placement: it ends the moves
     * started before it. Once a move starts after the last keyframe, the keyframe motion holds where it was so
     * the walk is not dragged towards the next keyframe; that keyframe then puts the actor exactly in place.
     */
    static double[] keyframeBase(ReplayProject.ActorData actor, int t) {
        int key = lastKeyframeTick(actor, t);
        for (ReplayProject.ActorAction move : actor.actions) {
            if (move.tick > t) {
                break;
            }
            if (isMove(move.actionType()) && move.tick >= key && parseVector(move.param) != null) {
                return Interpolation.actor(actor.keyframes, move.tick);
            }
        }
        return Interpolation.actor(actor.keyframes, t);
    }

    /**
     * Where an actor stands at {@code t}, as a placement sees it: keyframes and moves, without wander, pose or
     * jump offsets. Returns {x, y, z, yaw, pitch}, or null without keyframes nor fallback.
     */
    public static double[] placedState(ReplayProject.ActorData actor, int t, double[] fallback) {
        double[] base = keyframeBase(actor, t);
        if (base == null) {
            base = fallback;
        }
        if (base == null) {
            return null;
        }
        Frame f = new Frame();
        f.x = base[0];
        f.y = base[1];
        f.z = base[2];
        f.yaw = (float) base[3];
        applyMoves(f, actor, t, base);
        return new double[]{f.x, f.y, f.z, f.yaw, base[4]};
    }

    /**
     * Applies every WALK_TO / RUN_TO action started since the last keyframe, up to {@code t}, in order. Each move
     * goes from wherever the actor is when it starts to its destination, so moves chain into a route.
     */
    private static void applyMoves(Frame f, ReplayProject.ActorData actor, int t, double[] base) {
        int key = lastKeyframeTick(actor, t);
        double offX = 0;
        double offY = 0;
        double offZ = 0;
        for (ReplayProject.ActorAction move : actor.actions) {
            ActionType type = move.actionType();
            if (!isMove(type) || move.tick > t || move.tick < key) {
                continue;
            }
            double[] dest = parseVector(move.param);
            if (dest == null) {
                continue;
            }
            double sx = base[0] + offX;
            double sy = base[1] + offY;
            double sz = base[2] + offZ;
            double dx = dest[0] - sx;
            double dy = dest[1] - sy;
            double dz = dest[2] - sz;
            double distance = Math.sqrt(dx * dx + dz * dz);
            double speed = type == ActionType.RUN_TO ? RUN_SPEED : WALK_SPEED;
            int duration = move.duration > 0 ? move.duration : Math.max(1, (int) Math.ceil(distance / speed));
            double progress = Math.min(1.0, (t - move.tick) / (double) duration);
            offX += dx * progress;
            offY += dy * progress;
            offZ += dz * progress;
            if (progress < 1.0 && distance > 1.0E-3) {
                f.yaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
                f.forcedWalk = type == ActionType.RUN_TO ? 1.0F : 0.7F;
                f.sprint = type == ActionType.RUN_TO;
            }
        }
        f.x += offX;
        f.y += offY;
        f.z += offZ;
    }

    public static double[] parseVector(String param) {
        if (param == null) {
            return null;
        }
        String[] parts = param.trim().split("[\\s,;]+");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean shouldSnap(ReplayProject.ActorData actor, Entity entity, List<ReplayProject.ActorAction> actions, int t) {
        if (!actor.groundSnap || isFlyer(entity)) {
            return false;
        }
        // A sleeper keeps its keyframed height: a bed is lower than where a lying body rests on it. A sitter keeps
        // its seat height: snapping casts down from above the seat and lands on a chair back, a table or a ceiling.
        return activeState(actions, t, ActionType.FLY, ActionType.ELYTRA, ActionType.SWIM, ActionType.SPIN_ATTACK, ActionType.SLEEP,
                ActionType.SIT) == null;
    }

    private static boolean isFlyer(Entity entity) {
        return entity instanceof net.minecraft.world.entity.animal.FlyingAnimal
                || entity instanceof net.minecraft.world.entity.FlyingMob
                || entity instanceof net.minecraft.world.entity.ambient.Bat
                || entity instanceof net.minecraft.world.entity.monster.Vex
                || entity instanceof net.minecraft.world.entity.monster.Blaze
                || entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                || entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon;
    }

    /** Height of the ground under a point: searches a little above (steps, slabs) and well below. */
    public static double groundY(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return y;
        }
        Vec3 from = new Vec3(x, y + 1.25, z);
        Vec3 to = new Vec3(x, y - 8.0, z);
        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getLocation().y : y;
    }

    /** Where a FOLLOW target is now, or null when it cannot be found in the replay world. */
    static Vec3 followTarget(String name, ReplayProject project, ReplayProject.ActorData self) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        String key = name == null ? "" : name.trim();
        com.vyrriox.justreplay.replay.ReplaySession session = com.vyrriox.justreplay.replay.ReplaySession.current();
        if (key.isEmpty() || key.equalsIgnoreCase("recorder")) {
            Entity recorder = session == null ? null : session.findRecorder(mc);
            return recorder == null ? null : recorder.position();
        }
        for (ReplayProject.ActorData other : project.actors) {
            if (other != self && (other.name.equalsIgnoreCase(key) || other.id.equals(key)) && session != null) {
                Entity e = session.actors().entityOf(other);
                if (e != null) {
                    return e.position();
                }
            }
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (ActorManager.isActorEntity(e) || e == mc.player) {
                continue;
            }
            if (e.getUUID().toString().equalsIgnoreCase(key)
                    || (e instanceof Player p && p.getGameProfile().getName().equalsIgnoreCase(key))
                    || (e.hasCustomName() && e.getCustomName().getString().equalsIgnoreCase(key))) {
                return e.position();
            }
        }
        return null;
    }

    private static Vec3 lookTarget(String param, ReplayProject project, ReplayProject.ActorData self, int t) {
        String name = param == null ? "" : param.trim();
        if (name.isEmpty() || name.equalsIgnoreCase("camera")) {
            Minecraft mc = Minecraft.getInstance();
            return mc.gameRenderer.getMainCamera().getPosition();
        }
        for (ReplayProject.ActorData other : project.actors) {
            if (other != self && (other.name.equalsIgnoreCase(name) || other.id.equals(name))) {
                double[] pos = Interpolation.actor(other.keyframes, t);
                if (pos != null) {
                    return new Vec3(pos[0], pos[1] + 1.5, pos[2]);
                }
            }
        }
        return null;
    }

    private static double[] wanderOffset(double t, double radius, double seed) {
        double r = Math.max(0.25, radius);
        double w1 = 0.08 / r;
        double w2 = 0.13 / r;
        double x = r * (0.6 * Math.sin(t * w1 + seed) + 0.4 * Math.sin(t * w2 * 1.3 + seed * 2.1));
        double z = r * (0.6 * Math.cos(t * w1 * 0.9 + seed * 1.7) + 0.4 * Math.sin(t * w2 + seed * 0.3));
        return new double[]{x, z};
    }

    private static boolean isHumanoid(Entity entity) {
        return entity instanceof Player || entity instanceof Zombie || entity instanceof AbstractSkeleton
                || entity instanceof AbstractPiglin || entity instanceof AbstractIllager;
    }

    static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    static Pose parsePose(String name) {
        try {
            return Pose.valueOf(name);
        } catch (Exception e) {
            return Pose.STANDING;
        }
    }

    // ---------------------------------------------------------------- apply

    /**
     * Applies the frames of the current and previous tick to the entity, so the renderer interpolates between
     * them. One shot effects with a sound or particles fire only when the play head passes them while playing.
     */
    public void apply(ReplayProject.ActorData actor, ReplayProject project, Entity entity, int cursor, boolean paused) {
        State state = states.computeIfAbsent(actor.id, k -> new State());
        if (state.anchor == null) {
            // Without keyframes the actor stays where it spawned; actions add their offsets to that anchor.
            state.anchor = new double[]{entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot()};
        }
        double[] fallback = state.anchor;
        Frame now = frame(actor, project, cursor, entity, fallback);
        Frame before = paused ? now : frame(actor, project, cursor - 1, entity, fallback);
        applyFollow(actor, project, cursor, paused, state, now, before);

        entity.setPose(now.pose);
        entity.setShiftKeyDown(now.crouch);
        entity.setSprinting(now.sprint);
        entity.xo = entity.xOld = before.x;
        entity.yo = entity.yOld = before.y;
        entity.zo = entity.zOld = before.z;
        entity.yRotO = before.yaw;
        entity.xRotO = before.pitch;
        entity.setPos(now.x, now.y, now.z);
        entity.setYRot(now.yaw);
        entity.setXRot(now.pitch);
        entity.setDeltaMovement(now.fallFlying && !paused ? new Vec3(now.x - before.x, now.y - before.y, now.z - before.z) : Vec3.ZERO);
        if (!(entity instanceof Player)) {
            // Age drives idle motion and keyframed vanilla animations; the actor's own tick never advances it.
            entity.tickCount = Math.max(0, cursor);
        }

        if (entity instanceof LivingEntity living) {
            applyLiving(living, now, before, state, paused);
        }

        if (now.gesture != null) {
            GESTURES.put(entity.getId(), now.gesture);
        } else {
            GESTURES.remove(entity.getId());
        }
        if (now.dance != null) {
            DANCES.put(entity.getId(), now.dance);
        } else {
            DANCES.remove(entity.getId());
        }
        if (entity instanceof Player) {
            ReplayProject.ActorAction emote = activeState(actor.actions, cursor, ActionType.EMOTE);
            if (emote != null && emote.param != null && !emote.param.isBlank()) {
                com.vyrriox.justreplay.compat.EmotecraftCompat.applyActorEmote(entity, emote.param, cursor - emote.tick);
            } else {
                com.vyrriox.justreplay.compat.EmotecraftCompat.stopActorEmote(entity);
            }
        }
        if (now.sit && isHumanoid(entity)) {
            SITTING.add(entity.getId());
        } else {
            SITTING.remove(entity.getId());
        }
        if (entity instanceof TamableAnimal tamable) {
            tamable.setInSittingPose(now.sit);
        }
        if (entity instanceof Mob mob) {
            mob.setAggressive(now.aggressive);
        }

        if (!paused && state.lastCursor != Integer.MIN_VALUE && cursor > state.lastCursor && cursor - state.lastCursor <= 20) {
            fireCrossed(actor, entity, state.lastCursor, cursor);
        }
        state.lastCursor = cursor;
    }

    /**
     * FOLLOW keeps a live position (targets are recorded entities, known only at the current tick): the actor
     * walks towards its target, runs when far behind, stops at the requested distance and faces it.
     */
    private static void applyFollow(ReplayProject.ActorData actor, ReplayProject project, int cursor,
                                    boolean paused, State state, Frame now, Frame before) {
        ReplayProject.ActorAction follow = activeState(actor.actions, cursor, ActionType.FOLLOW);
        // A keyframe placed after the follow started (sitting down, going to bed, a move) ends it.
        if (follow != null && lastKeyframeTick(actor, cursor) > follow.tick) {
            follow = null;
        }
        if (follow == null) {
            state.follow = null;
            return;
        }
        String[] parts = (follow.param == null ? "" : follow.param).split("\\|");
        double distance = parts.length > 1 ? parseDouble(parts[1], 2.5) : 2.5;
        Vec3 target = followTarget(parts[0], project, actor);
        if (state.follow == null) {
            state.follow = new double[]{now.x, now.y, now.z, now.yaw};
        }
        double[] prev = state.follow.clone();
        if (target != null && !paused && cursor != state.lastFollowCursor) {
            double dx = target.x - state.follow[0];
            double dz = target.z - state.follow[2];
            double gap = Math.sqrt(dx * dx + dz * dz);
            if (gap > 24.0) {
                // Lost after a seek or a teleport: appear behind the target.
                state.follow[0] = target.x - dx / gap * distance;
                state.follow[2] = target.z - dz / gap * distance;
            } else if (gap > distance) {
                double step = Math.min(gap - distance, gap > distance + 4.0 ? RUN_SPEED : WALK_SPEED);
                state.follow[0] += dx / gap * step;
                state.follow[2] += dz / gap * step;
            }
            if (gap > 1.0E-3) {
                state.follow[3] = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
            }
            state.follow[1] = actor.groundSnap ? groundY(state.follow[0], target.y, state.follow[2]) : target.y;
            state.lastFollowCursor = cursor;
        }
        double moved = Math.hypot(state.follow[0] - prev[0], state.follow[2] - prev[2]);
        now.x = state.follow[0];
        now.y = state.follow[1];
        now.z = state.follow[2];
        now.yaw = (float) state.follow[3];
        now.headYaw = now.yaw;
        if (moved > 1.0E-3) {
            now.forcedWalk = moved > WALK_SPEED * 1.1 ? 1.0F : 0.7F;
            now.sprint = moved > WALK_SPEED * 1.1;
        }
        before.x = paused ? now.x : prev[0];
        before.y = paused ? now.y : prev[1];
        before.z = paused ? now.z : prev[2];
        before.yaw = paused ? now.yaw : (float) prev[3];
    }

    private void applyLiving(LivingEntity living, Frame now, Frame before, State state, boolean paused) {
        living.yBodyRotO = before.yaw;
        living.yBodyRot = now.yaw;
        // Fields, not setYHeadRot: armor stands (model actors) would also overwrite the body rotation.
        living.yHeadRotO = before.headYaw;
        living.yHeadRot = now.headYaw;

        // Walk cycle from the horizontal speed along the path, like vanilla's calculateEntityAnimation.
        WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) living.walkAnimation;
        if (paused) {
            if (living instanceof Player) {
                // Players keep ticking while paused: a moving phase would twitch, so the legs rest.
                walk.justreplay$setSpeedOld(0.0F);
                walk.justreplay$setSpeed(0.0F);
                walk.justreplay$setPosition(state.walkPosition);
            }
        } else {
            double dx = now.x - before.x;
            double dz = now.z - before.z;
            float target = (float) Math.min(1.0, Math.sqrt(dx * dx + dz * dz) * 4.0);
            target = Math.max(target, now.forcedWalk);
            if (now.pose == Pose.SLEEPING || now.deathTime > 0 || now.sit) {
                target = 0.0F;
            }
            float old = state.walkSpeed;
            state.walkSpeed = old + (target - old) * 0.4F;
            state.walkPosition += state.walkSpeed;
            walk.justreplay$setSpeedOld(old);
            walk.justreplay$setSpeed(state.walkSpeed);
            walk.justreplay$setPosition(state.walkPosition);
        }

        living.oAttackAnim = before.attackAnim;
        living.attackAnim = now.attackAnim;
        living.swinging = now.swingTime >= 0;
        living.swingTime = Math.max(0, now.swingTime);
        living.swingingArm = now.swingHand;

        living.hurtDuration = HURT_TICKS;
        living.hurtTime = now.hurtTime;
        living.deathTime = now.deathTime;

        LivingEntityAccessor access = (LivingEntityAccessor) living;
        access.justreplay$setSwimAmountO(before.swimAmount);
        access.justreplay$setSwimAmount(now.swimAmount);
        access.justreplay$setLivingEntityFlag(4, now.spin);

        if (living instanceof Player player) {
            if (now.fallFlying && !state.fallFlying) {
                player.startFallFlying();
            } else if (!now.fallFlying && state.fallFlying) {
                player.stopFallFlying();
            }
        } else {
            ((EntityInvoker) living).justreplay$setSharedFlag(7, now.fallFlying);
        }
        state.fallFlying = now.fallFlying;
        access.justreplay$setFallFlyTicks(now.fallFlying ? Math.max(0, now.fallFlyTicks) : 0);

        applyUse(living, access, now);

        int counter = now.attackCounter;
        if (living instanceof IronGolem) {
            ((IronGolemAccessor) living).justreplay$setAttackAnimation(counter);
        } else if (living instanceof Ravager) {
            ((RavagerAccessor) living).justreplay$setAttackAnimation(counter);
        } else if (living instanceof Hoglin) {
            ((HoglinAccessor) living).justreplay$setAttackAnimation(counter);
        } else if (living instanceof Zoglin) {
            ((ZoglinAccessor) living).justreplay$setAttackAnimation(counter);
        }
    }

    private static void applyUse(LivingEntity living, LivingEntityAccessor access, Frame now) {
        if (now.use == null) {
            access.justreplay$setLivingEntityFlag(1, false);
            return;
        }
        InteractionHand hand = useHand(living, now.use, now.useParam);
        if (living.isUsingItem() && living.getUseItem() != living.getItemInHand(hand)) {
            // Another hand or item: turn the use off so the client picks the new item up below.
            access.justreplay$setLivingEntityFlag(1, false);
        }
        // The hand flag first: the client picks the used item from it when the "using" flag turns on.
        access.justreplay$setLivingEntityFlag(2, hand == InteractionHand.OFF_HAND);
        access.justreplay$setLivingEntityFlag(1, true);
        ItemStack used = living.getUseItem();
        if (used.isEmpty()) {
            return;
        }
        int duration = used.getUseDuration(living);
        if (duration <= 0) {
            return;
        }
        int remaining;
        if (now.use == ActionType.EAT || used.getUseAnimation() == UseAnim.EAT || used.getUseAnimation() == UseAnim.DRINK) {
            remaining = duration - (now.useElapsed % duration);
        } else {
            remaining = Math.max(1, duration - now.useElapsed);
        }
        access.justreplay$setUseItemRemaining(remaining);
    }

    private static InteractionHand useHand(LivingEntity living, ActionType type, String param) {
        UseAnim wanted = switch (type) {
            case BLOCK -> UseAnim.BLOCK;
            case BOW -> UseAnim.BOW;
            case EAT -> UseAnim.EAT;
            default -> null;
        };
        if (wanted == null) {
            return param.trim().equalsIgnoreCase("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            UseAnim anim = living.getItemInHand(hand).getUseAnimation();
            if (anim == wanted || (wanted == UseAnim.BOW && anim == UseAnim.CROSSBOW) || (wanted == UseAnim.EAT && anim == UseAnim.DRINK)) {
                return hand;
            }
        }
        return type == ActionType.BLOCK ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** Sounds and particles of actions whose start lies in (from, to]. */
    private static void fireCrossed(ReplayProject.ActorData actor, Entity entity, int from, int to) {
        for (ReplayProject.ActorAction action : actor.actions) {
            ActionType type = action.actionType();
            int at = type == ActionType.DEATH ? action.tick + 18 : action.tick;
            if (at <= from || at > to) {
                continue;
            }
            try {
                switch (type) {
                    case HURT -> {
                        if (entity instanceof LivingEntity living) {
                            living.handleDamageEvent(living.damageSources().generic());
                        }
                    }
                    case ATTACK -> {
                        if (entity instanceof IronGolem || entity instanceof Ravager || entity instanceof Hoglin
                                || entity instanceof Zoglin || entity instanceof Warden) {
                            entity.handleEntityEvent((byte) 4);
                        }
                    }
                    case DEATH -> {
                        if (entity instanceof LivingEntity) {
                            entity.handleEntityEvent((byte) 60);
                        }
                    }
                    case ENTITY_EVENT -> entity.handleEntityEvent((byte) (int) parseDouble(action.param, 0));
                    default -> {
                    }
                }
            } catch (Throwable t) {
                com.vyrriox.justreplay.JustReplay.LOGGER.warn("Actor {} action {} failed: {}", actor.name, type, t.toString());
            }
        }
    }
}
