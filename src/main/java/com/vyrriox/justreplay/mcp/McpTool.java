package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One tool of the MCP server: its name, what it does, the schema of its arguments and the code that answers it. The
 * code runs on the game thread unless the tool is built with {@link Builder#offGameThread()}, in which case it must
 * reach the game only through {@link McpContext#onGameThread}.
 *
 * @author vyrriox
 */
public final class McpTool {
    /** Letters, digits, underscore, hyphen and dot, 1 to 128 of them, as the specification asks of tool names. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    /**
     * What a tool may do to the game, checked before every call against the MCP settings, whatever the tool's own
     * code does: see {@link McpProtocol.Guards}.
     */
    public enum Access {
        /** Only reads. */
        READ,
        /** Changes the game or the open project (project edits stay undoable); refused while the server is read only. */
        CHANGE,
        /** Deletes files for good (a replay, an export): also refused unless the player allowed it in the settings. */
        DELETE
    }

    /** Answers a call. Throw {@link McpArgs.Invalid} for a wrong argument: the caller gets a tool error it can fix. */
    @FunctionalInterface
    public interface Handler {
        McpToolResult call(McpArgs args, McpContext context) throws Exception;
    }

    private final String name;
    private final String title;
    private final String description;
    private final JsonObject inputSchema;
    private final Set<String> argumentNames;
    private final JsonObject annotations;
    private final Access access;
    private final boolean onGameThread;
    private final long minTimeoutMillis;
    private final Handler handler;

    private McpTool(Builder b) {
        this.name = b.name;
        this.title = b.title;
        this.description = b.description;
        this.inputSchema = b.schema.build();
        this.argumentNames = Set.copyOf(b.schema.names());
        this.annotations = b.annotations;
        this.access = b.access;
        this.onGameThread = b.onGameThread;
        this.minTimeoutMillis = b.minTimeoutSeconds * 1000L;
        this.handler = b.handler;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public boolean onGameThread() {
        return onGameThread;
    }

    /** What the tool may do, for the settings that limit MCP clients. */
    public Access access() {
        return access;
    }

    /** Shortest time a call may take before it fails, whatever the settings say (0: the settings decide). */
    public long minTimeoutMillis() {
        return minTimeoutMillis;
    }

    Set<String> argumentNames() {
        return argumentNames;
    }

    Handler handler() {
        return handler;
    }

    /** The entry of {@code tools/list}. */
    JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (title != null) {
            o.addProperty("title", title);
        }
        o.addProperty("description", description);
        o.add("inputSchema", inputSchema.deepCopy());
        if (annotations.size() > 0) {
            o.add("annotations", annotations.deepCopy());
        }
        return o;
    }

    public static final class Builder {
        private final String name;
        private String title;
        private String description;
        private McpSchema schema = McpSchema.object();
        private final JsonObject annotations = new JsonObject();
        /** A tool that says nothing about itself is taken for one that changes things. */
        private Access access = Access.CHANGE;
        private boolean onGameThread = true;
        private int minTimeoutSeconds;
        private Handler handler;

        private Builder(String name) {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid tool name: " + name);
            }
            this.name = name;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder input(McpSchema schema) {
            this.schema = Objects.requireNonNull(schema);
            return this;
        }

        /** Marks the tool as one that only reads: it changes nothing, so calling it again gives the same answer. */
        public Builder readOnly() {
            access = Access.READ;
            annotations.addProperty("readOnlyHint", true);
            annotations.addProperty("destructiveHint", false);
            annotations.addProperty("idempotentHint", true);
            annotations.addProperty("openWorldHint", false);
            return this;
        }

        /** Marks a tool that changes things but only adds or moves them (a marker, a keyframe, the play head): nothing is lost. */
        public Builder additive() {
            return changes(Access.CHANGE, false);
        }

        /**
         * Marks a tool that may overwrite or remove what is there (an edit of the project, which {@code undo} takes
         * back, or the recording it stops).
         */
        public Builder destructive() {
            return changes(Access.CHANGE, true);
        }

        /** Marks a tool that deletes files for good: refused unless the player allowed it in the settings. */
        public Builder deletesFiles() {
            return changes(Access.DELETE, true);
        }

        /** Calling it again with the same arguments changes nothing more (after additive, destructive or deletesFiles). */
        public Builder idempotent() {
            annotations.addProperty("idempotentHint", true);
            return this;
        }

        private Builder changes(Access access, boolean destructive) {
            this.access = access;
            annotations.addProperty("readOnlyHint", false);
            annotations.addProperty("destructiveHint", destructive);
            annotations.addProperty("idempotentHint", false);
            annotations.addProperty("openWorldHint", false);
            return this;
        }

        /** Runs the handler on the network thread; it reaches the game through {@link McpContext#onGameThread}. */
        public Builder offGameThread() {
            this.onGameThread = false;
            return this;
        }

        /**
         * Lets a call take at least {@code seconds} before it fails, for a tool that waits on the game for more than
         * a tick (drawing a frame, moving through the replay). The longer of this and the settings applies.
         */
        public Builder timeoutSeconds(int seconds) {
            this.minTimeoutSeconds = Math.max(0, seconds);
            return this;
        }

        public Builder handler(Handler handler) {
            this.handler = handler;
            return this;
        }

        public McpTool build() {
            if (description == null || description.isEmpty()) {
                throw new IllegalStateException("Tool " + name + " has no description");
            }
            if (handler == null) {
                throw new IllegalStateException("Tool " + name + " has no handler");
            }
            if (title != null) {
                annotations.addProperty("title", title);
            }
            return new McpTool(this);
        }
    }
}
