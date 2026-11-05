package com.vyrriox.justreplay.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;

/**
 * Tick rate and freeze of the client level, the two things pause and slow motion need. This Minecraft version has no
 * tick rate manager, so this class holds its rules and the time mixins read them.
 * <p>
 * The state belongs to one level, like a manager held by the level: a new level (a dimension change, the end of the
 * replay) runs normally until {@link #apply} is called for it, so nothing has to be reset when a replay stops.
 *
 * @author vyrriox
 */
public final class TimeControl {
    public static final float DEFAULT_RATE = 20.0F;
    public static final float DEFAULT_MS_PER_TICK = 1000.0F / DEFAULT_RATE;
    private static final float MIN_RATE = 1.0F;

    private static volatile WeakReference<Level> level = new WeakReference<>(null);
    private static volatile boolean frozen;
    private static volatile float rate = DEFAULT_RATE;
    private static long heldBlockEntityTicks;

    private TimeControl() {
    }

    public static void apply(Level target, float tickRate, boolean freeze) {
        level = new WeakReference<>(target);
        rate = Math.max(tickRate, MIN_RATE);
        frozen = freeze;
    }

    /** The current client level is frozen. */
    public static boolean isFrozen() {
        return isFrozen(Minecraft.getInstance().level);
    }

    /** A check made from common code too: a server level is never the level held here. */
    public static boolean isFrozen(Level target) {
        return frozen && target != null && target == level.get();
    }

    /** Players and whatever carries one keep ticking and keep moving between ticks. */
    public static boolean isEntityFrozen(Entity entity) {
        return isFrozen(entity.level()) && !(entity instanceof Player) && !carriesPlayer(entity);
    }

    private static boolean carriesPlayer(Entity entity) {
        if (entity.isVehicle()) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                if (passenger instanceof Player) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Length of a client tick. A frozen level ticks at the normal rate, and a client never runs above it. */
    public static float millisPerTick() {
        Level current = Minecraft.getInstance().level;
        if (current == null || current != level.get() || frozen) {
            return DEFAULT_MS_PER_TICK;
        }
        return Math.max(DEFAULT_MS_PER_TICK, 1000.0F / rate);
    }

    /** Called for each block entity tick a freeze holds back, read by the self test. */
    public static void countHeldBlockEntityTick() {
        heldBlockEntityTicks++;
    }

    public static long heldBlockEntityTicks() {
        return heldBlockEntityTicks;
    }
}
