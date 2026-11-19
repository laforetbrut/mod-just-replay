package com.vyrriox.justreplay.format;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markers placed while recording: the ones a clip of the instant replay buffer keeps, moved to the clip's ticks.
 *
 * @author vyrriox
 */
class RecordingMarkersTest {
    private static ReplayMeta.Marker m(int tick, String name) {
        return new ReplayMeta.Marker(tick, name);
    }

    private static List<String> describe(List<ReplayMeta.Marker> markers) {
        return markers.stream().map(marker -> marker.name + "@" + marker.tick).toList();
    }

    @Test
    void clipKeepsItsWindowMovedToTickZero() {
        List<ReplayMeta.Marker> placed = List.of(m(100, "Marker 1"), m(1300, "Marker 2"), m(2500, "Marker 3"));
        // A clip of capture ticks 1200 to 2400: only the second marker, 100 ticks into the clip.
        assertEquals(List.of("Marker 2@100"), describe(RecordingMarkers.window(placed, 1200, 2400)));
    }

    @Test
    void windowEdgesAreIncluded() {
        List<ReplayMeta.Marker> placed = List.of(m(1200, "start"), m(2400, "end"), m(1199, "before"), m(2401, "after"));
        assertEquals(List.of("start@0", "end@1200"), describe(RecordingMarkers.window(placed, 1200, 2400)));
    }

    @Test
    void markersComeOutSortedAndCopied() {
        ReplayMeta.Marker late = m(50, "late");
        List<ReplayMeta.Marker> placed = new ArrayList<>(List.of(late, m(10, "early")));
        List<ReplayMeta.Marker> kept = RecordingMarkers.window(placed, 0, 100);
        assertEquals(List.of("early@10", "late@50"), describe(kept));
        // The recorder's own list is never changed by a save.
        assertEquals(50, late.tick);
        assertTrue(kept.get(1) != late);
    }

    @Test
    void nothingToKeep() {
        assertTrue(RecordingMarkers.window(null, 0, 100).isEmpty());
        assertTrue(RecordingMarkers.window(List.of(), 0, 100).isEmpty());
        assertTrue(RecordingMarkers.window(List.of(m(5, "a")), 10, 5).isEmpty());
        List<ReplayMeta.Marker> withHoles = Arrays.asList(null, m(5, null));
        assertEquals(List.of("@5"), describe(RecordingMarkers.window(withHoles, 0, 10)));
    }

    @Test
    void numbersFollowTheMarkersOfTheOutput() {
        List<ReplayMeta.Marker> placed = List.of(m(100, "Marker 1"), m(1300, "Marker 2"), m(2500, "Marker 3"));
        assertEquals(3, RecordingMarkers.countFrom(placed, 0));
        // A recording started at capture tick 1000 holds two of them.
        assertEquals(2, RecordingMarkers.countFrom(placed, 1000));
        assertEquals(0, RecordingMarkers.countFrom(placed, 3000));
        assertEquals(0, RecordingMarkers.countFrom(null, 0));
    }

    @Test
    void clockShowsMinutesThenHours() {
        assertEquals("0:00", RecordingMarkers.clock(0));
        assertEquals("0:00", RecordingMarkers.clock(-40));
        assertEquals("0:01", RecordingMarkers.clock(39));
        assertEquals("1:24", RecordingMarkers.clock(84 * 20));
        assertEquals("59:59", RecordingMarkers.clock(3599 * 20));
        assertEquals("1:00:00", RecordingMarkers.clock(3600 * 20));
        assertEquals("2:03:04", RecordingMarkers.clock((2 * 3600 + 3 * 60 + 4) * 20));
    }
}
