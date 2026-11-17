package com.vyrriox.justreplay.replay;

import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;

/**
 * Added to the client clock manager, which keeps the rate and the partial tick of each clock private. A replay needs it to tell a stopped
 * day cycle from a running one, as 1.21.1 did with the daylight game rule.
 *
 * @author vyrriox
 */
public interface ClockRates {
    /** Rate last sent for a clock, 1 when none was sent yet (the default of the manager). */
    float justreplay$rate(Holder<WorldClock> clock);

    /** Fraction of a tick of a clock, 0 for a clock the manager has not seen yet. */
    float justreplay$partialTick(Holder<WorldClock> clock);
}
