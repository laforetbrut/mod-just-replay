package com.vyrriox.justreplay.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Video time of replay ticks under a speed track: held speeds, ramps, timelapses, freezes, and the way back.
 *
 * @author vyrriox
 */
class TimeMapTest {
    private static final double EPS = 1.0E-6;

    private static SpeedTrack track() {
        return new SpeedTrack();
    }

    @Test
    void withoutATrackVideoTimeIsReplayTime() {
        TimeMap map = TimeMap.of(null, 100, 300);
        assertTrue(map.identity());
        assertEquals(10.0, map.durationSeconds(), EPS);
        assertEquals(200.0, map.tickAt(5.0), EPS);
        assertEquals(5.0, map.secondsAt(200), EPS);
        assertEquals(1.0, map.speedAt(3.0), EPS);
        assertTrue(map.soundsAt(3.0));
        // 10 s at 60 fps: frame 0 shows tick 100, frame 600 tick 300.
        assertEquals(601, map.frames(60));
        assertTrue(TimeMap.of(track(), 0, 20).identity());
    }

    @Test
    void heldHalfSpeedLastsTwiceAsLong() {
        SpeedTrack t = track();
        t.setKey(0, 0.5F);
        TimeMap map = TimeMap.of(t, 1000, 1040);
        // Two replay seconds at x0.5: four seconds of video, as the self test exports.
        assertEquals(4.0, map.durationSeconds(), EPS);
        assertEquals(241, map.frames(60));
        assertEquals(1020.0, map.tickAt(2.0), EPS);
        assertEquals(2.0, map.secondsAt(1020), EPS);
        assertEquals(0.5, map.speedAt(1.0), EPS);
    }

    @Test
    void keysChangeTheSpeedFromTheirTick() {
        SpeedTrack t = track();
        t.setKey(100, 2.0F);
        TimeMap map = TimeMap.of(t, 0, 200);
        // x1 before the first key, x2 after it.
        assertEquals(5.0 + 2.5, map.durationSeconds(), EPS);
        assertEquals(150.0, map.tickAt(6.25), EPS);
        assertEquals(5.0, map.secondsAt(100), EPS);
        assertEquals(1.0, map.speedAt(4.9), EPS);
        assertEquals(2.0, map.speedAt(5.1), EPS);
    }

    @Test
    void rampsChangeTheSpeedSmoothly() {
        SpeedTrack t = track();
        t.keys.add(new SpeedTrack.Key(0, 1.0F, true));
        t.keys.add(new SpeedTrack.Key(100, 2.0F, false));
        TimeMap map = TimeMap.of(t, 0, 100);
        // Speed 1 + u over the ramp: 5 s times the integral of 1 / (1 + u) from 0 to 1.
        assertEquals(5.0 * Math.log(2.0), map.durationSeconds(), 1.0E-6);
        assertEquals(1.5, t.speedAt(50), EPS);
        assertEquals(50.0, map.tickAt(map.secondsAt(50)), 1.0E-6);
    }

    @Test
    void timelapsePlaysItsRangeInItsLength() {
        SpeedTrack t = track();
        t.timelapses.add(new SpeedTrack.Timelapse(100, 1300, 6.0F));
        TimeMap map = TimeMap.of(t, 0, 1400);
        assertEquals(5.0 + 6.0 + 5.0, map.durationSeconds(), EPS);
        assertEquals(700.0, map.tickAt(8.0), EPS);
        assertEquals(10.0, t.speedAt(700), EPS);
        // The game sounds are cut at x10.
        assertFalse(map.soundsAt(8.0));
        assertTrue(map.soundsAt(2.0));
    }

    @Test
    void freezeHoldsThePicture() {
        SpeedTrack t = track();
        t.freezes.add(new SpeedTrack.Freeze(100, 2.0F));
        TimeMap map = TimeMap.of(t, 0, 200);
        assertEquals(12.0, map.durationSeconds(), EPS);
        assertEquals(100.0, map.tickAt(5.0), EPS);
        assertEquals(100.0, map.tickAt(6.0), EPS);
        assertEquals(100.0, map.tickAt(6.999), EPS);
        assertEquals(110.0, map.tickAt(7.5), EPS);
        assertTrue(map.frozenAt(6.0));
        assertFalse(map.frozenAt(7.5));
        assertFalse(map.frozenAt(4.0));
        assertEquals(0.0, map.speedAt(6.0), EPS);
        assertFalse(map.soundsAt(6.0));
        // The replay reaches tick 100 when the freeze starts; tick 110 half a second after it.
        assertEquals(5.0, map.secondsAt(100), EPS);
        assertEquals(7.5, map.secondsAt(110), EPS);
    }

    @Test
    void freezeOnTheLastTickEndsTheVideo() {
        SpeedTrack t = track();
        t.freezes.add(new SpeedTrack.Freeze(40, 1.5F));
        TimeMap map = TimeMap.of(t, 0, 40);
        assertEquals(3.5, map.durationSeconds(), EPS);
        assertEquals(40.0, map.tickAt(3.0), EPS);
    }

    @Test
    void wayBackMatchesOutsideFreezes() {
        SpeedTrack t = track();
        t.keys.add(new SpeedTrack.Key(20, 0.25F, true));
        t.keys.add(new SpeedTrack.Key(80, 4.0F, false));
        t.timelapses.add(new SpeedTrack.Timelapse(300, 900, 3.0F));
        t.freezes.add(new SpeedTrack.Freeze(150, 1.0F));
        TimeMap map = TimeMap.of(t, 0, 1000);
        for (double tick : List.of(0.0, 10.5, 20.0, 47.3, 80.0, 149.0, 151.25, 300.0, 512.5, 899.0, 950.0, 1000.0)) {
            assertEquals(tick, map.tickAt(map.secondsAt(tick)), 1.0E-6, "tick " + tick);
        }
        double previous = -1.0;
        for (double s = 0.0; s <= map.durationSeconds(); s += 0.01) {
            double tick = map.tickAt(s);
            assertTrue(tick >= previous - EPS, "video time never goes back in the replay");
            previous = tick;
        }
    }

    @Test
    void rangeStartingLaterUsesTheTrackThere() {
        SpeedTrack t = track();
        t.setKey(500, 0.5F);
        assertEquals(1.0, TimeMap.of(t, 0, 20).durationSeconds(), EPS);
        assertEquals(2.0, TimeMap.of(t, 600, 620).durationSeconds(), EPS);
        assertEquals(1.0 + 2.0, TimeMap.of(t, 480, 520).durationSeconds(), EPS);
    }

    @Test
    void normalizeRepairsTheTrack() {
        SpeedTrack t = track();
        t.keys.add(new SpeedTrack.Key(50, 20.0F, false));
        t.keys.add(new SpeedTrack.Key(10, 0.0F, false));
        t.keys.add(new SpeedTrack.Key(50, 3.0F, true));
        t.keys.add(null);
        t.timelapses.add(new SpeedTrack.Timelapse(100, 50, 2.0F));
        t.timelapses.add(new SpeedTrack.Timelapse(200, 400, 2.0F));
        t.timelapses.add(new SpeedTrack.Timelapse(300, 500, 2.0F));
        t.freezes.add(new SpeedTrack.Freeze(5, 0.0F));
        t.freezes.add(new SpeedTrack.Freeze(5, 9999.0F));
        t.soundMin = 3.0F;
        t.soundMax = 1.0F;
        t.normalize();
        assertEquals(2, t.keys.size());
        assertEquals(SpeedTrack.MIN_SPEED, t.keys.get(0).speed, EPS);
        // One key per tick: the later one in the file stays.
        assertEquals(3.0F, t.keys.get(1).speed, EPS);
        assertEquals(1, t.timelapses.size());
        assertEquals(200, t.timelapses.get(0).from);
        assertEquals(1, t.freezes.size());
        assertEquals(SpeedTrack.MAX_SECONDS, t.freezes.get(0).seconds, EPS);
        assertEquals(1.0F, t.soundMin, EPS);
        assertEquals(3.0F, t.soundMax, EPS);
    }

    @Test
    void setKeyReplacesTheKeyOnATick() {
        SpeedTrack t = track();
        t.setKey(40, 2.0F);
        t.keyAt(40).ramp = true;
        t.setKey(40, 0.5F);
        t.setKey(10, 99.0F);
        assertEquals(2, t.keys.size());
        assertEquals(10, t.keys.get(0).tick);
        assertEquals(SpeedTrack.MAX_SPEED, t.keys.get(0).speed, EPS);
        assertEquals(0.5F, t.keyAt(40).speed, EPS);
        assertTrue(t.keyAt(40).ramp);
    }
}
