package com.vyrriox.justreplay.record;

/**
 * When the capture builds a checkpoint, for its two outputs: the recording on disk and the instant replay buffer.
 * <p>
 * Each output keeps its own calendar. It is due one interval after its last checkpoint (its start counts as one) and
 * takes any checkpoint built at least nine tenths of an interval after it, so one checkpoint serves both outputs
 * whenever it fits both. Neither output waits for the other's phase: a recording started while the buffer runs gets
 * its first checkpoint one interval after its start (it used to wait for the buffer's clock, up to almost two
 * intervals), and the buffer keeps its own spacing however often recordings start. While the two phases stay more
 * than a tenth of an interval apart, the recording costs one more snapshot per interval; each output still compresses
 * only the checkpoints it takes.
 * <p>
 * One checkpoint is built at a time; the caller knows when one is being built. A checkpoint that fails or is dropped
 * (the level changed while its chunks were built) is tried again after {@link #RETRY_TICKS}, twice as late after
 * each new failure in a row, never later than the shortest interval. Client thread only; no game class.
 *
 * @author vyrriox
 */
public final class CheckpointSchedule {
    /** Ticks before the first new try after a checkpoint failed or was dropped. */
    public static final int RETRY_TICKS = 100;

    /** Which outputs take a finished checkpoint. */
    public record Taken(boolean recording, boolean buffer) {
        /** Whether any output takes it. */
        public boolean any() {
            return recording || buffer;
        }
    }

    /** The calendar of one output. */
    public static final class Calendar {
        private Object output;
        private int interval = 1;
        private int last;
        private boolean forced;
        private int forcedFrom;
        private int taken;

        /**
         * Follows the output running now, called before every decision: a new output starts the calendar at its start
         * tick, which counts as a checkpoint, and null stops it. The interval follows the settings.
         *
         * @param output    the running output (compared by identity), or null
         * @param startTick capture tick the output starts at
         * @param interval  ticks between two checkpoints of this output
         */
        public void follow(Object output, int startTick, int interval) {
            if (output != this.output) {
                this.output = output;
                last = startTick;
                forced = false;
                taken = 0;
            }
            this.interval = Math.max(1, interval);
        }

        /** Whether an output runs. */
        public boolean active() {
            return output != null;
        }

        /** The output wants a checkpoint now: the next one built from this tick on is taken whatever its spacing. */
        public void force(int tick) {
            if (output != null) {
                forced = true;
                forcedFrom = tick;
            }
        }

        /** Whether a forced checkpoint is still awaited. */
        public boolean forced() {
            return forced;
        }

        /** Capture tick of the last checkpoint taken, or of the start. */
        public int last() {
            return last;
        }

        /** Checkpoints taken since the output started. */
        public int taken() {
            return taken;
        }

        /** Ticks between two checkpoints of this output. */
        public int interval() {
            return interval;
        }

        boolean due(int tick) {
            return output != null && (forced || tick - last >= interval);
        }

        boolean fits(int at) {
            if (output == null) {
                return false;
            }
            if (forced && at >= forcedFrom) {
                return true;
            }
            return at - last >= interval - interval / 10;
        }

        void take(int at) {
            last = at;
            taken++;
            if (forced && at >= forcedFrom) {
                forced = false;
            }
        }
    }

    private final Calendar recording = new Calendar();
    private final Calendar buffer = new Calendar();
    private int retryAt = Integer.MIN_VALUE;
    private int retryDelay = RETRY_TICKS;

    /** Calendar of the recording on disk. */
    public Calendar recording() {
        return recording;
    }

    /** Calendar of the instant replay buffer. */
    public Calendar buffer() {
        return buffer;
    }

    /** Whether a checkpoint should be built at this tick, none being built: an output is due and no failure waits. */
    public boolean wants(int tick) {
        return tick >= retryAt && (recording.due(tick) || buffer.due(tick));
    }

    /** A checkpoint built at capture tick {@code at} is complete: every output it fits takes it. */
    public Taken delivered(int at) {
        retryAt = Integer.MIN_VALUE;
        retryDelay = RETRY_TICKS;
        boolean forRecording = recording.fits(at);
        boolean forBuffer = buffer.fits(at);
        if (forRecording) {
            recording.take(at);
        }
        if (forBuffer) {
            buffer.take(at);
        }
        return new Taken(forRecording, forBuffer);
    }

    /** The checkpoint asked for failed or was dropped at this tick: wait before the next one, longer each time. */
    public void failed(int tick) {
        retryAt = tick + retryDelay;
        retryDelay = Math.min(Math.max(RETRY_TICKS, shortestInterval()), retryDelay * 2);
    }

    /** Ticks until a new try is allowed after the failures so far, 0 when none waits. */
    public int retryWait(int tick) {
        return retryAt == Integer.MIN_VALUE ? 0 : Math.max(0, retryAt - tick);
    }

    private int shortestInterval() {
        int shortest = Integer.MAX_VALUE;
        if (recording.active()) {
            shortest = recording.interval;
        }
        if (buffer.active()) {
            shortest = Math.min(shortest, buffer.interval);
        }
        return shortest == Integer.MAX_VALUE ? RETRY_TICKS : shortest;
    }
}
