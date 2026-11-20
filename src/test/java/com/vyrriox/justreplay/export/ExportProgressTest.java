package com.vyrriox.justreplay.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the render veil shows: share done, time spent and left, and the GUI scale of the real window.
 *
 * @author vyrriox
 */
class ExportProgressTest {
    @Test
    void fractionStaysBetweenZeroAndOne() {
        assertEquals(0.0F, ExportProgress.fraction(0, 100));
        assertEquals(0.25F, ExportProgress.fraction(25, 100));
        assertEquals(1.0F, ExportProgress.fraction(100, 100));
        assertEquals(1.0F, ExportProgress.fraction(120, 100));
        assertEquals(0.0F, ExportProgress.fraction(5, 0));
        assertEquals(0.0F, ExportProgress.fraction(-3, 100));
    }

    @Test
    void percentOnlyReadsHundredWhenDone() {
        assertEquals(0, ExportProgress.percent(0, 600));
        assertEquals(20, ExportProgress.percent(120, 600));
        assertEquals(99, ExportProgress.percent(599, 600));
        assertEquals(99, ExportProgress.percent(9_999, 10_000));
        assertEquals(100, ExportProgress.percent(600, 600));
        assertEquals(0, ExportProgress.percent(10, 0));
    }

    @Test
    void remainingWaitsForAPace() {
        assertEquals(-1L, ExportProgress.remainingSeconds(5, 600, 10_000));
        assertEquals(-1L, ExportProgress.remainingSeconds(50, 600, 1_000));
        assertEquals(-1L, ExportProgress.remainingSeconds(50, 0, 10_000));
    }

    @Test
    void remainingFollowsThePace() {
        // 120 frames in 12 s: 480 frames left take 48 s.
        assertEquals(48L, ExportProgress.remainingSeconds(120, 600, 12_000));
        // 0.25 s left is still one second, never 0:00 before the end.
        assertEquals(1L, ExportProgress.remainingSeconds(599, 600, 150_000));
        assertEquals(0L, ExportProgress.remainingSeconds(600, 600, 60_000));
    }

    @Test
    void clockReadsLikeAStopwatch() {
        assertEquals("0:00", ExportProgress.clock(0));
        assertEquals("0:05", ExportProgress.clock(5));
        assertEquals("12:34", ExportProgress.clock(754));
        assertEquals("59:59", ExportProgress.clock(3599));
        assertEquals("1:00:00", ExportProgress.clock(3600));
        assertEquals("1:02:03", ExportProgress.clock(3723));
        assertEquals("-:--", ExportProgress.clock(-1));
    }

    @Test
    void guiScaleMatchesTheGame() {
        assertEquals(2, ExportProgress.guiScale(854, 480, 0, false));
        assertEquals(3, ExportProgress.guiScale(1280, 720, 0, false));
        assertEquals(4, ExportProgress.guiScale(1920, 1080, 0, false));
        assertEquals(9, ExportProgress.guiScale(3840, 2160, 0, false));
        // A chosen scale is a ceiling, never more than the window allows.
        assertEquals(2, ExportProgress.guiScale(1920, 1080, 2, false));
        assertEquals(2, ExportProgress.guiScale(854, 480, 6, false));
        // The Unicode font wants an even scale.
        assertEquals(4, ExportProgress.guiScale(1280, 720, 0, true));
        assertEquals(1, ExportProgress.guiScale(200, 100, 0, false));
    }
}
