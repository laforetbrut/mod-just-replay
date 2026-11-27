package com.vyrriox.justreplay.mcp;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands work from the network threads to the game thread. {@link #call} queues the work and waits for its result; the
 * game thread runs what is queued with {@link #runPending}, once per tick. Work whose caller stopped waiting (deadline
 * passed) never starts, and the queue holds a bounded number of calls, so a flood of requests cannot pile up work for
 * the game.
 *
 * @author vyrriox
 */
public final class GameThreadQueue {
    /** Why a call could not get an answer from the game. */
    public static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;

        public enum Reason {
            /** The game did not run or finish the work before the deadline. */
            TIMEOUT,
            /** Too many calls are already waiting. */
            BUSY,
            /** The queue is closed: the server is stopping. */
            CLOSED
        }

        private final Reason reason;

        Unavailable(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int SKIPPED = 2;

    private static final class Task<T> {
        final Callable<T> work;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicInteger state = new AtomicInteger(PENDING);

        Task(Callable<T> work) {
            this.work = work;
        }

        void run() {
            try {
                result.complete(work.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }
    }

    private final int capacity;
    private final ConcurrentLinkedQueue<Task<?>> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile boolean closed;
    private volatile Thread gameThread;

    public GameThreadQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Runs {@code work} on the game thread and waits at most {@code timeoutMillis} for its result. On the game thread
     * itself (once it has run {@link #runPending}) the work runs at once, so a game-thread caller cannot wait on itself.
     *
     * @throws Unavailable when the game does not answer in time, too many calls wait or the queue is closed
     * @throws Exception   what the work threw
     */
    public <T> T call(Callable<T> work, long timeoutMillis) throws Exception {
        if (Thread.currentThread() == gameThread) {
            return work.call();
        }
        if (closed) {
            throw new Unavailable(Unavailable.Reason.CLOSED, "The MCP server is stopping");
        }
        if (queued.incrementAndGet() > capacity) {
            queued.decrementAndGet();
            throw new Unavailable(Unavailable.Reason.BUSY, "Too many requests are waiting for the game; try again in a moment");
        }
        Task<T> task = new Task<>(work);
        tasks.add(task);
        if (closed && task.state.compareAndSet(PENDING, SKIPPED)) {
            // Closed between the check and the add: close() may already have drained the queue.
            throw new Unavailable(Unavailable.Reason.CLOSED, "The MCP server is stopping");
        }
        try {
            return task.result.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            boolean neverStarted = task.state.compareAndSet(PENDING, SKIPPED);
            throw new Unavailable(Unavailable.Reason.TIMEOUT, neverStarted
                    ? "Minecraft did not answer within " + seconds(timeoutMillis) + " (it may be loading a world or frozen); try again"
                    : "Minecraft took longer than " + seconds(timeoutMillis) + " to answer; try again");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        } catch (InterruptedException e) {
            task.state.compareAndSet(PENDING, SKIPPED);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Game thread: runs the queued work, oldest first, until the queue is empty or {@code budgetNanos} have passed
     * (one piece of work always runs). Work whose caller gave up is dropped. Returns how many pieces ran.
     */
    public int runPending(long budgetNanos) {
        gameThread = Thread.currentThread();
        long start = System.nanoTime();
        int ran = 0;
        Task<?> task;
        while ((task = tasks.poll()) != null) {
            queued.decrementAndGet();
            if (task.state.compareAndSet(PENDING, RUNNING)) {
                task.run();
                ran++;
                if (System.nanoTime() - start >= budgetNanos) {
                    break;
                }
            }
        }
        return ran;
    }

    /** Refuses new calls and fails the waiting ones; {@link #open} takes calls again. */
    public void close() {
        closed = true;
        Task<?> task;
        while ((task = tasks.poll()) != null) {
            queued.decrementAndGet();
            if (task.state.compareAndSet(PENDING, SKIPPED)) {
                task.result.completeExceptionally(new Unavailable(Unavailable.Reason.CLOSED, "The MCP server is stopping"));
            }
        }
    }

    public void open() {
        closed = false;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Calls waiting for the game thread. */
    public int size() {
        return Math.max(0, queued.get());
    }

    private static String seconds(long millis) {
        long s = Math.max(1L, Math.round(millis / 1000.0));
        return s + (s == 1 ? " second" : " seconds");
    }
}
