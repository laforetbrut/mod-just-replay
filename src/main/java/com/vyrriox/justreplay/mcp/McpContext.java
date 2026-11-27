package com.vyrriox.justreplay.mcp;

import java.util.concurrent.Callable;

/**
 * What a tool or resource reader may use besides its arguments: the way to the game thread, within the time left to
 * the request.
 *
 * @author vyrriox
 */
public final class McpContext {
    private final GameThreadQueue queue;
    private final long deadlineNanos;
    private final String protocolVersion;

    McpContext(GameThreadQueue queue, long deadlineNanos, String protocolVersion) {
        this.queue = queue;
        this.deadlineNanos = deadlineNanos;
        this.protocolVersion = protocolVersion;
    }

    /**
     * Runs {@code work} on the game thread and returns its result. Called from the game thread it runs at once.
     *
     * @throws GameThreadQueue.TimeoutException when the game does not answer before the request's deadline
     */
    public <T> T onGameThread(Callable<T> work) throws Exception {
        return queue.call(work, remainingMillis());
    }

    /** Milliseconds left before the request fails, at least 1. */
    public long remainingMillis() {
        return Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    /** The protocol version the request uses. */
    public String protocolVersion() {
        return protocolVersion;
    }
}
