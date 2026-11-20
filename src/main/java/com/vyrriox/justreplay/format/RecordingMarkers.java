package com.vyrriox.justreplay.format;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Markers placed while recording (the recording marker key): the part of them a saved clip keeps, and their time.
 * Plain Java, no game code.
 *
 * @author vyrriox
 */
public final class RecordingMarkers {
    private RecordingMarkers() {
    }

    /**
     * The markers from tick {@code from} to tick {@code to} (both included), moved so that {@code from} becomes tick
     * 0, sorted by tick. Used when the instant replay buffer saves its last minutes: their markers keep their place in
     * the clip. Null entries and a null list give nothing.
     */
    public static List<ReplayMeta.Marker> window(List<ReplayMeta.Marker> markers, int from, int to) {
        List<ReplayMeta.Marker> kept = new ArrayList<>();
        if (markers == null || to < from) {
            return kept;
        }
        for (ReplayMeta.Marker marker : markers) {
            if (marker != null && marker.tick >= from && marker.tick <= to) {
                kept.add(new ReplayMeta.Marker(marker.tick - from, marker.name == null ? "" : marker.name));
            }
        }
        kept.sort(Comparator.comparingInt(m -> m.tick));
        return kept;
    }

    /** Number of markers from tick {@code from} on, the count a new marker's number follows. */
    public static int countFrom(List<ReplayMeta.Marker> markers, int from) {
        int count = 0;
        if (markers != null) {
            for (ReplayMeta.Marker marker : markers) {
                if (marker != null && marker.tick >= from) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Recording time of a tick (20 ticks a second): {@code m:ss}, or {@code h:mm:ss} from one hour. */
    public static String clock(int tick) {
        int seconds = Math.max(0, tick) / 20;
        int hours = seconds / 3600;
        int minutes = seconds / 60 % 60;
        int rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
