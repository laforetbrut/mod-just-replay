package com.vyrriox.justreplay.record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Packets a recording or a replay leaves out. A packet that cannot be encoded (recording), read back or played
 * (replay), a mod payload above all, is skipped and counted: the recording or the replay goes on with the next one.
 * The first of each type and stage logs one warning; a summary line gives the counts when the recording or the replay
 * ends. No game class and thread safe (the network and the client threads both record).
 *
 * @author vyrriox
 */
public final class SkippedPackets {
    /** Where a packet was lost. */
    public enum Stage {
        RECORD("could not be recorded"),
        READ("could not be read"),
        PLAY("failed to play"),
        NO_RECEIVER("have no receiving mod here");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Most packet types the summary names; the others are counted together. */
    static final int SUMMARY_TYPES = 12;

    private record Key(Stage stage, String type) {
    }

    private final String owner;
    private final BiConsumer<String, Throwable> warn;
    private final Map<Key, LongAdder> counts = new ConcurrentHashMap<>();

    /**
     * Counts for {@code owner} ("Recording", "Replay"), whose warnings go to {@code warn} (message, error).
     */
    public SkippedPackets(String owner, BiConsumer<String, Throwable> warn) {
        this.owner = owner;
        this.warn = warn;
    }

    /** Counts a skipped packet of this type; true when it is the first of its type at this stage. */
    public boolean count(Stage stage, String type) {
        Key key = new Key(stage, type == null ? "unknown packet" : type);
        LongAdder count = counts.get(key);
        boolean first = false;
        if (count == null) {
            LongAdder fresh = new LongAdder();
            count = counts.putIfAbsent(key, fresh);
            if (count == null) {
                count = fresh;
                first = true;
            }
        }
        count.increment();
        return first;
    }

    /** Counts a packet skipped because of {@code error}; the first of its type at this stage logs a warning. */
    public void fail(Stage stage, String type, Throwable error) {
        if (count(stage, type)) {
            warn.accept(owner + ": " + (type == null ? "unknown packet" : type) + " " + stage.label()
                    + ", skipped (later ones of this type are only counted): " + error, error);
        }
    }

    /**
     * Decodes one packet: an unreadable one (its mod changed or is missing, the bytes are damaged) is counted and gives
     * null, so the caller moves on to the next one. {@code type} names the packet from its raw bytes.
     */
    public <T> T read(byte[] data, Function<byte[], T> decoder, Function<byte[], String> type) {
        try {
            return decoder.apply(data);
        } catch (Throwable t) {
            String name;
            try {
                name = type.apply(data);
            } catch (Throwable ignored) {
                name = null;
            }
            fail(Stage.READ, name, t);
            return null;
        }
    }

    /** How many packets of this type were skipped at this stage. */
    public long countOf(Stage stage, String type) {
        LongAdder count = counts.get(new Key(stage, type));
        return count == null ? 0 : count.sum();
    }

    /** Every packet skipped since the last {@link #flush}. */
    public long total() {
        long total = 0;
        for (LongAdder count : counts.values()) {
            total += count.sum();
        }
        return total;
    }

    /** One line with the counts, the most frequent types first, or null when nothing was skipped. */
    public String summary() {
        List<Map.Entry<Key, Long>> entries = new ArrayList<>();
        for (Map.Entry<Key, LongAdder> entry : counts.entrySet()) {
            entries.add(Map.entry(entry.getKey(), entry.getValue().sum()));
        }
        if (entries.isEmpty()) {
            return null;
        }
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long total = 0;
        for (Map.Entry<Key, Long> entry : entries) {
            total += entry.getValue();
        }
        StringBuilder out = new StringBuilder(owner).append(": ").append(total).append(total == 1 ? " packet" : " packets")
                .append(" skipped (");
        int shown = Math.min(entries.size(), SUMMARY_TYPES);
        for (int i = 0; i < shown; i++) {
            Map.Entry<Key, Long> entry = entries.get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append(entry.getValue()).append(" x ").append(entry.getKey().type()).append(' ').append(entry.getKey().stage().label());
        }
        if (entries.size() > shown) {
            long rest = 0;
            for (int i = shown; i < entries.size(); i++) {
                rest += entries.get(i).getValue();
            }
            out.append(", ").append(rest).append(" of ").append(entries.size() - shown).append(" other types");
        }
        return out.append(')').toString();
    }

    /** Hands the summary to {@code log} when something was skipped, then counts from zero again. */
    public void flush(Consumer<String> log) {
        String summary = summary();
        counts.clear();
        if (summary != null) {
            log.accept(summary);
        }
    }
}
