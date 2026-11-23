package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.JustReplayCore;

import java.net.BindException;

/**
 * Keeps the MCP server in step with the settings. The game calls {@link #tick} once per client tick: the server
 * starts when the settings switch it on, restarts when its port or token change, stops when switched off, and the
 * calls waiting for the game run then, on the game thread. A port that cannot be used is not tried again until the
 * settings change.
 *
 * @author vyrriox
 */
public final class McpHost {
    /** At most this much of a tick goes to MCP calls; the rest waits for the next tick. */
    private static final long TICK_BUDGET_NANOS = 5_000_000L;
    /** Calls that may wait for the game at once. */
    private static final int QUEUE_SIZE = 32;

    /** What the game shows about the server. */
    public enum Status {
        OFF,
        LISTENING,
        /** Switched on, but the port could not be used: see {@link #error()}. */
        FAILED
    }

    /** Told on the game thread when the server starts, stops or cannot start. */
    public interface Events {
        void started(int port);

        void stopped();

        void failed(int port, String reason);
    }

    private final McpConfig config;
    private final GameThreadQueue queue = new GameThreadQueue(QUEUE_SIZE);
    private final McpProtocol protocol;
    private final Events events;
    private McpHttpServer server;
    private String runningKey;
    private String failedKey;
    private volatile Status status = Status.OFF;
    private volatile String error = "";
    private volatile int port = -1;

    public McpHost(McpConfig config, McpRegistry registry, McpProtocol.Info info, Events events) {
        this.config = config;
        this.events = events;
        this.protocol = new McpProtocol(registry, queue, info, () -> config.timeoutSeconds.get() * 1000L,
                McpProtocol.Guards.of(config.readOnly::get, config.allowDestructive::get));
    }

    /** Game thread, once per tick. */
    public void tick() {
        sync();
        if (server != null) {
            queue.runPending(TICK_BUDGET_NANOS);
        }
    }

    private void sync() {
        if (!config.enabled.get()) {
            failedKey = null;
            if (status == Status.FAILED) {
                status = Status.OFF;
                error = "";
            }
            if (server != null) {
                stopServer();
                events.stopped();
            }
            return;
        }
        String token = config.token.get();
        if (!McpToken.usable(token)) {
            token = McpToken.generate();
            config.token.set(token);
            JustReplayCore.LOGGER.info("MCP: a new access token was written to the settings ({} section)", McpConfig.SECTION);
        }
        int wanted = config.port.get();
        String key = wanted + "\n" + token;
        if (server != null) {
            if (key.equals(runningKey)) {
                return;
            }
            stopServer();
        }
        if (key.equals(failedKey)) {
            return;
        }
        start(wanted, token, key);
    }

    private void start(int wanted, String token, String key) {
        McpHttpServer created = null;
        queue.open();
        try {
            created = new McpHttpServer(protocol, token, config.allowedOrigins::get);
            created.start(wanted);
        } catch (Exception | LinkageError e) {
            queue.close();
            failedKey = key;
            status = Status.FAILED;
            error = reason(e, wanted);
            JustReplayCore.LOGGER.warn("MCP server cannot listen on 127.0.0.1:{}: {}", wanted, error);
            events.failed(wanted, error);
            return;
        }
        server = created;
        runningKey = key;
        failedKey = null;
        status = Status.LISTENING;
        error = "";
        port = created.port();
        JustReplayCore.LOGGER.info("MCP server listening on http://127.0.0.1:{}{}", port, McpHttpServer.PATH);
        events.started(port);
    }

    private void stopServer() {
        McpHttpServer s = server;
        server = null;
        runningKey = null;
        queue.close();
        s.stop();
        status = Status.OFF;
        port = -1;
        JustReplayCore.LOGGER.info("MCP server stopped");
    }

    /** Stops the server without telling {@link Events}, when the game quits. */
    public void shutdown() {
        if (server != null) {
            stopServer();
        }
    }

    public Status status() {
        return status;
    }

    public boolean isListening() {
        return status == Status.LISTENING;
    }

    /** The port listened on, -1 when not listening. */
    public int port() {
        return port;
    }

    /** Why the server could not start, empty otherwise. */
    public String error() {
        return error;
    }

    /** Tool calls and resource reads answered since the game started. */
    public long calls() {
        return protocol.calls();
    }

    /** {@link System#currentTimeMillis()} of the last call, 0 before the first. */
    public long lastCallMillis() {
        return protocol.lastCallMillis();
    }

    private static String reason(Throwable e, int port) {
        if (e instanceof BindException) {
            return "port " + port + " is already used by another program";
        }
        if (e instanceof LinkageError) {
            return "this Java has no HTTP server (module jdk.httpserver)";
        }
        return McpProtocol.describe(e);
    }
}
