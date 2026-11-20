package com.vyrriox.justreplay.record;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recorded mod packet that cannot be read or played is skipped and counted; the replay goes on with the next one.
 *
 * @author vyrriox
 */
class SkippedPacketsTest {
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final SkippedPackets skipped = new SkippedPackets("Replay", (message, error) -> warnings.add(message));

    /** Packets of this stream are one byte: 0xFF is a mod payload whose codec throws. */
    private static final Function<byte[], String> DECODER = data -> {
        if (data[0] == (byte) 0xFF) {
            throw new IllegalArgumentException("Unknown testmod:broken payload layout");
        }
        return "packet " + data[0];
    };
    private static final Function<byte[], String> TYPE = data -> data[0] == (byte) 0xFF ? "testmod:broken" : "packet #" + data[0];

    @Test
    void unreadablePacketIsSkippedAndCounted() {
        byte[][] stream = {{1}, {(byte) 0xFF}, {2}, {(byte) 0xFF}, {3}};
        List<String> played = new ArrayList<>();
        for (byte[] data : stream) {
            String packet = skipped.read(data, DECODER, TYPE);
            if (packet != null) {
                played.add(packet);
            }
        }
        assertEquals(List.of("packet 1", "packet 2", "packet 3"), played, "the replay goes on after an unreadable packet");
        assertEquals(2, skipped.countOf(SkippedPackets.Stage.READ, "testmod:broken"));
        assertEquals(1, warnings.size(), "one warning for the type, the next ones are counted");
        assertTrue(warnings.get(0).contains("testmod:broken") && warnings.get(0).contains("could not be read"), warnings.get(0));
    }

    @Test
    void packetThatCannotBeNamedIsStillCounted() {
        Function<byte[], String> brokenType = data -> {
            throw new IndexOutOfBoundsException("empty packet");
        };
        assertNull(skipped.read(new byte[0], data -> {
            throw new IndexOutOfBoundsException("empty packet");
        }, brokenType));
        assertEquals(1, skipped.total());
        assertEquals(1, warnings.size());
    }

    @Test
    void summaryCountsEveryStageThenStartsAgain() {
        skipped.fail(SkippedPackets.Stage.PLAY, "ClientboundTestPacket", new NullPointerException("entity"));
        for (int i = 0; i < 3; i++) {
            skipped.read(new byte[]{(byte) 0xFF}, DECODER, TYPE);
        }
        skipped.count(SkippedPackets.Stage.NO_RECEIVER, "othermod:sync");
        assertEquals("Replay: 5 packets skipped (3 x testmod:broken could not be read, 1 x ClientboundTestPacket failed to play,"
                + " 1 x othermod:sync have no receiving mod here)", reorderTies(skipped.summary()));
        List<String> logged = new ArrayList<>();
        skipped.flush(logged::add);
        assertEquals(1, logged.size());
        assertNull(skipped.summary(), "counting starts again after the summary");
        skipped.flush(logged::add);
        assertEquals(1, logged.size(), "nothing skipped, nothing logged");

        skipped.fail(SkippedPackets.Stage.PLAY, "ClientboundTestPacket", new NullPointerException("entity"));
        assertEquals(3, warnings.size(), "a new replay warns again for a type");
    }

    @Test
    void summaryNamesTheMostFrequentTypes() {
        for (int type = 0; type < SkippedPackets.SUMMARY_TYPES + 3; type++) {
            for (int n = 0; n <= type; n++) {
                skipped.count(SkippedPackets.Stage.READ, "testmod:type" + type);
            }
        }
        String summary = skipped.summary();
        assertTrue(summary.contains("15 x testmod:type14"), summary);
        assertTrue(summary.endsWith("6 of 3 other types)"), summary);
    }

    @Test
    void countsFromSeveralThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        skipped.fail(SkippedPackets.Stage.RECORD, "testmod:big", new IllegalStateException("too large"));
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(4000, skipped.countOf(SkippedPackets.Stage.RECORD, "testmod:big"));
        assertEquals(1, warnings.size());
    }

    /** Types with the same count may come in any order: the two of count 1 are put in a fixed order. */
    private static String reorderTies(String summary) {
        return summary.replace("1 x othermod:sync have no receiving mod here, 1 x ClientboundTestPacket failed to play",
                "1 x ClientboundTestPacket failed to play, 1 x othermod:sync have no receiving mod here");
    }
}
