package com.vyrriox.justreplay.record;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoint calendars of the recording and of the instant replay buffer, run through a small model of the capture
 * loop: one checkpoint built at a time, taking a few ticks, asked for at the end of a tick.
 *
 * @author vyrriox
 */
class CheckpointScheduleTest {
    /** The capture loop of the recorder, reduced to the checkpoint decisions. */
    private static final class Capture {
        final CheckpointSchedule schedule = new CheckpointSchedule();
        final int buildTicks;
        final List<Integer> recording = new ArrayList<>();
        final List<Integer> buffer = new ArrayList<>();
        int builds;
        Object rec;
        int recStart;
        int recInterval;
        Object buf;
        int bufStart;
        int bufInterval;
        private int pendingAt = -1;
        private int pendingDone;

        Capture(int buildTicks) {
            this.buildTicks = buildTicks;
        }

        void startBuffer(int tick, int interval) {
            buf = new Object();
            bufStart = tick;
            bufInterval = interval;
        }

        void startRecording(int tick, int interval) {
            rec = new Object();
            recStart = tick;
            recInterval = interval;
        }

        void stopRecording() {
            rec = null;
        }

        void follow() {
            schedule.recording().follow(rec, recStart, recInterval);
            schedule.buffer().follow(buf, bufStart, bufInterval);
        }

        /** One client tick: a finished checkpoint is handed out, then a new one is asked for if an output wants it. */
        void tick(int t) {
            follow();
            if (pendingAt >= 0 && t >= pendingDone) {
                CheckpointSchedule.Taken taken = schedule.delivered(pendingAt);
                if (taken.recording()) {
                    recording.add(pendingAt);
                }
                if (taken.buffer()) {
                    buffer.add(pendingAt);
                }
                pendingAt = -1;
            }
            if (pendingAt < 0 && schedule.wants(t)) {
                pendingAt = t;
                pendingDone = t + buildTicks;
                builds++;
            }
        }

        void run(int from, int to) {
            for (int t = from; t < to; t++) {
                tick(t);
            }
        }
    }

    private static List<Integer> relative(List<Integer> ticks, int start) {
        List<Integer> out = new ArrayList<>();
        for (int tick : ticks) {
            out.add(tick - start);
        }
        return out;
    }

    private static List<Integer> gaps(List<Integer> ticks, int start) {
        List<Integer> out = new ArrayList<>();
        int last = start;
        for (int tick : ticks) {
            out.add(tick - last);
            last = tick;
        }
        return out;
    }

    @Test
    void recordingStartedAfterTheBufferGetsItsCheckpointsFromItsOwnStart() {
        // The test replay of the measures: one minute on both sides, the recording started 141 ticks after a buffer
        // checkpoint. Following the buffer's clock, its checkpoints were at 2259 and 3459.
        Capture c = new Capture(20);
        c.startBuffer(0, 1200);
        c.run(0, 1341);
        c.startRecording(1341, 1200);
        c.run(1341, 1341 + 4821);
        assertEquals(List.of(1200, 2400, 3600, 4800), relative(c.recording, 1341));
        assertEquals(List.of(1200, 2400, 3600, 4800, 6000), c.buffer);
        // The phases differ by more than a tenth of an interval: one snapshot more per recording checkpoint.
        assertEquals(c.buffer.size() + c.recording.size(), c.builds);
    }

    @Test
    void defaultSettingsNeverDelayEitherOutput() {
        // Checkpoints every 5 minutes, a 5 minute buffer (one checkpoint a minute), at every phase between them.
        for (int offset = 0; offset < 1200; offset += 7) {
            Capture c = new Capture(0);
            c.startBuffer(0, 1200);
            int start = 6000 + offset;
            c.run(0, start);
            c.startRecording(start, 6000);
            // Checkpoints are handed out the tick after they are asked for (an instant build here).
            c.run(start, start + 30_002);
            List<Integer> recordingGaps = gaps(c.recording, start);
            assertEquals(5, c.recording.size(), "offset " + offset);
            for (int gap : recordingGaps) {
                assertTrue(gap >= 5400 && gap <= 6000, "offset " + offset + ": recording gap " + gap);
            }
            for (int gap : gaps(c.buffer, 0)) {
                assertTrue(gap >= 1080 && gap <= 1200, "offset " + offset + ": buffer gap " + gap);
            }
            if (offset <= 600 || offset >= 1080) {
                // A buffer checkpoint lands in the recording's last tenth, or the buffer takes the recording's one:
                // from then on every recording checkpoint is a buffer checkpoint too.
                assertEquals(c.buffer.size(), c.builds, "offset " + offset);
            } else {
                assertEquals(c.buffer.size() + c.recording.size(), c.builds, "offset " + offset);
            }
        }
    }

    @Test
    void phasesWithinATenthShareEveryCheckpoint() {
        Capture c = new Capture(20);
        c.startBuffer(0, 1200);
        c.run(0, 60);
        c.startRecording(60, 1200);
        c.run(60, 4900);
        assertEquals(List.of(1140, 2340, 3540, 4740), relative(c.recording, 60));
        assertEquals(c.buffer.size(), c.builds);
    }

    @Test
    void bufferKeepsItsSpacingWhenRecordingsStartOften() {
        // Short recordings started every 40 seconds: none is long enough for a checkpoint, and none delays the buffer.
        Capture c = new Capture(20);
        c.startBuffer(0, 1200);
        int t = 0;
        for (int k = 0; k < 30; k++) {
            int start = 100 + 800 * k;
            c.run(t, start);
            c.startRecording(start, 1200);
            c.run(start, start + 500);
            c.stopRecording();
            t = start + 500;
        }
        c.run(t, 24_021);
        assertTrue(c.recording.isEmpty());
        for (int gap : gaps(c.buffer, 0)) {
            assertEquals(1200, gap);
        }
        assertEquals(20, c.buffer.size());
        assertEquals(c.buffer.size(), c.builds);
    }

    @Test
    void longBufferWindowAndShortRecordingInterval() {
        // A 60 minute buffer (a checkpoint every 12 minutes) with checkpoints every minute in the recording.
        Capture c = new Capture(40);
        c.startBuffer(0, 14_400);
        c.run(0, 500);
        c.startRecording(500, 1200);
        c.run(500, 60_000);
        for (int gap : gaps(c.recording, 500)) {
            assertTrue(gap >= 1080 && gap <= 1240, "recording gap " + gap);
        }
        for (int gap : gaps(c.buffer, 0)) {
            assertTrue(gap >= 12_960 && gap <= 14_440, "buffer gap " + gap);
        }
    }

    @Test
    void aRecordingAloneFollowsItsOwnStart() {
        Capture c = new Capture(30);
        c.run(0, 777);
        c.startRecording(777, 6000);
        c.run(777, 777 + 18_031);
        assertEquals(List.of(6000, 12_000, 18_000), relative(c.recording, 777));
        assertTrue(c.buffer.isEmpty());
        assertEquals(3, c.builds);
    }

    @Test
    void outputsNeverWaitMoreThanTheirIntervalAndABuild() {
        Random random = new Random(514);
        for (int run = 0; run < 300; run++) {
            int rec = 1200 * (1 + random.nextInt(30));
            int buf = Math.max(1200, 240 * (1 + random.nextInt(120)));
            int build = random.nextInt(601);
            int offset = random.nextInt(2 * buf);
            Capture c = new Capture(build);
            c.startBuffer(0, buf);
            c.run(0, offset);
            c.startRecording(offset, rec);
            int end = offset + 4 * Math.max(rec, buf) + build + 1;
            c.run(offset, end);
            String config = "rec " + rec + " buf " + buf + " build " + build + " offset " + offset;
            assertFalse(c.recording.isEmpty(), config);
            for (int gap : gaps(c.recording, offset)) {
                assertTrue(gap >= rec - rec / 10 && gap <= rec + build, config + ": recording gap " + gap);
            }
            for (int gap : gaps(c.buffer, 0)) {
                assertTrue(gap >= buf - buf / 10 && gap <= buf + build, config + ": buffer gap " + gap);
            }
            assertTrue(c.builds <= c.recording.size() + c.buffer.size() + 1, config + ": builds " + c.builds);
        }
    }

    @Test
    void forcedCheckpointIsTakenWhateverTheSpacing() {
        CheckpointSchedule s = new CheckpointSchedule();
        Object recording = new Object();
        s.recording().follow(recording, 100, 60);
        assertFalse(s.wants(110));
        s.recording().force(110);
        assertTrue(s.recording().forced());
        assertTrue(s.wants(110));
        assertEquals(new CheckpointSchedule.Taken(true, false), s.delivered(110));
        assertFalse(s.recording().forced());
        assertEquals(110, s.recording().last());
        assertEquals(1, s.recording().taken());
        assertFalse(s.wants(169));
        assertTrue(s.wants(170));
    }

    @Test
    void forcedCheckpointWaitsPastAnOlderOne() {
        CheckpointSchedule s = new CheckpointSchedule();
        Object recording = new Object();
        s.recording().follow(recording, 100, 60);
        s.recording().force(150);
        // Built before the force and too early for the spacing: not taken, the force still waits.
        assertFalse(s.delivered(149).recording());
        assertTrue(s.recording().forced());
        assertTrue(s.delivered(150).recording());
        assertFalse(s.recording().forced());

        s.recording().force(300);
        // Older than the force but far enough from the last one: taken as a regular checkpoint, the force still waits.
        assertTrue(s.delivered(250).recording());
        assertTrue(s.recording().forced());
        assertTrue(s.delivered(301).recording());
        assertFalse(s.recording().forced());
        assertEquals(3, s.recording().taken());
    }

    @Test
    void failuresAreTriedAgainLaterAndLater() {
        CheckpointSchedule s = new CheckpointSchedule();
        s.recording().follow(new Object(), 0, 1200);
        assertTrue(s.wants(1200));
        s.failed(1200);
        assertFalse(s.wants(1299));
        assertEquals(1, s.retryWait(1299));
        assertTrue(s.wants(1300));
        int tick = 1300;
        int[] waits = {200, 400, 800, 1200, 1200};
        for (int wait : waits) {
            s.failed(tick);
            assertFalse(s.wants(tick + wait - 1), "wait " + wait);
            assertTrue(s.wants(tick + wait), "wait " + wait);
            tick += wait;
        }
        s.delivered(tick);
        assertEquals(0, s.retryWait(tick));
        s.failed(tick + 1200);
        assertTrue(s.wants(tick + 1200 + CheckpointSchedule.RETRY_TICKS));
    }

    @Test
    void retriesNeverWaitLongerThanTheShortestInterval() {
        CheckpointSchedule s = new CheckpointSchedule();
        s.recording().follow(new Object(), 0, 36_000);
        s.buffer().follow(new Object(), 0, 1440);
        int tick = 1440;
        int wait = 0;
        for (int i = 0; i < 12; i++) {
            s.failed(tick);
            wait = s.retryWait(tick);
            tick += wait;
        }
        assertEquals(1440, wait);
    }

    @Test
    void stoppedOutputsTakeNothing() {
        CheckpointSchedule s = new CheckpointSchedule();
        s.recording().follow(null, 0, 60);
        s.buffer().follow(null, 0, 60);
        assertFalse(s.recording().active());
        assertFalse(s.wants(10_000));
        s.recording().force(10);
        assertFalse(s.recording().forced());
        CheckpointSchedule.Taken taken = s.delivered(10_000);
        assertFalse(taken.any());
    }

    @Test
    void aNewOutputRestartsItsCalendar() {
        CheckpointSchedule s = new CheckpointSchedule();
        Object first = new Object();
        s.recording().follow(first, 0, 1200);
        assertTrue(s.delivered(1200).recording());
        s.recording().force(1300);
        Object second = new Object();
        s.recording().follow(second, 5000, 1200);
        assertEquals(5000, s.recording().last());
        assertEquals(0, s.recording().taken());
        assertFalse(s.recording().forced());
        // A checkpoint built before the new recording started is not its own.
        assertFalse(s.delivered(4999).recording());
        assertFalse(s.wants(6199));
        assertTrue(s.wants(6200));
    }

    @Test
    void intervalsFollowTheSettings() {
        CheckpointSchedule s = new CheckpointSchedule();
        Object recording = new Object();
        s.recording().follow(recording, 0, 6000);
        assertFalse(s.wants(1200));
        s.recording().follow(recording, 0, 1200);
        assertEquals(1200, s.recording().interval());
        assertTrue(s.wants(1200));
        s.recording().follow(recording, 0, 0);
        assertEquals(1, s.recording().interval());
    }
}
