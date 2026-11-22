package com.vyrriox.justreplay.mcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue between the network threads and the game thread.
 *
 * @author vyrriox
 */
class GameThreadQueueTest {
    /** Waits until {@code queue} holds {@code n} calls. */
    private static void awaitSize(GameThreadQueue queue, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.size() < n && System.nanoTime() < end) {
            Thread.sleep(2);
        }
        assertEquals(n, queue.size());
    }

    @Test
    void workRunsOnTheThreadThatRunsThePendingCalls() throws Exception {
        GameThreadQueue queue = new GameThreadQueue(4);
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> {
            try {
                return queue.call(() -> Thread.currentThread().getName(), 5000);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitSize(queue, 1);
        Thread game = new Thread(() -> queue.runPending(1_000_000_000L), "game");
        game.start();
        game.join();
        assertEquals("game", answer.get(5, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void theGameThreadItselfRunsAtOnce() throws Exception {
        GameThreadQueue queue = new GameThreadQueue(1);
        queue.runPending(0);
        assertEquals(42, queue.call(() -> 42, 10));
    }

    @Test
    void aCallPastItsDeadlineNeverRuns() throws Exception {
        GameThreadQueue queue = new GameThreadQueue(4);
        AtomicInteger runs = new AtomicInteger();
        GameThreadQueue.Unavailable e = assertThrows(GameThreadQueue.Unavailable.class, () -> queue.call(runs::incrementAndGet, 50));
        assertEquals(GameThreadQueue.Unavailable.Reason.TIMEOUT, e.reason());
        assertEquals(0, queue.runPending(1_000_000_000L));
        assertEquals(0, runs.get());
    }

    @Test
    void aFullQueueSaysBusy() throws Exception {
        GameThreadQueue queue = new GameThreadQueue(1);
        CompletableFuture<Object> first = CompletableFuture.supplyAsync(() -> {
            try {
                return queue.call(() -> "first", 5000);
            } catch (Exception e) {
                return e;
            }
        });
        awaitSize(queue, 1);
        GameThreadQueue.Unavailable busy = assertThrows(GameThreadQueue.Unavailable.class, () -> queue.call(() -> "second", 50));
        assertEquals(GameThreadQueue.Unavailable.Reason.BUSY, busy.reason());
        queue.runPending(1_000_000_000L);
        assertEquals("first", first.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closingFailsTheWaitingCallsAndRefusesNewOnes() throws Exception {
        GameThreadQueue queue = new GameThreadQueue(4);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Object> waiting = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                return queue.call(() -> "never", 5000);
            } catch (Exception e) {
                return e;
            }
        });
        started.await();
        awaitSize(queue, 1);
        queue.close();
        Object outcome = waiting.get(5, TimeUnit.SECONDS);
        assertInstanceOf(GameThreadQueue.Unavailable.class, outcome);
        assertEquals(GameThreadQueue.Unavailable.Reason.CLOSED, ((GameThreadQueue.Unavailable) outcome).reason());
        assertThrows(GameThreadQueue.Unavailable.class, () -> queue.call(() -> 1, 50));
        queue.open();
        assertTrue(!queue.isClosed());
    }

    @Test
    void whatTheWorkThrowsReachesTheCaller() throws Exception {
        GameThreadQueue queue = new GameThreadQueue(4);
        IllegalStateException boom = new IllegalStateException("boom");
        CompletableFuture<Object> call = CompletableFuture.supplyAsync(() -> {
            try {
                return queue.call(() -> {
                    throw boom;
                }, 5000);
            } catch (Exception e) {
                return e;
            }
        });
        awaitSize(queue, 1);
        queue.runPending(1_000_000_000L);
        assertSame(boom, call.get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> CompletableFuture.failedFuture(boom).get());
    }
}
