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
    private final boolean onGameThread;
    private final Handler handler;

    private McpTool(Builder b) {
        this.name = b.name;
        this.title = b.title;
        this.description = b.description;
        this.inputSchema = b.schema.build();
        this.argumentNames = Set.copyOf(b.schema.names());
        this.annotations = b.annotations;
        this.onGameThread = b.onGameThread;
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
        private boolean onGameThread = true;
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
            annotations.addProperty("readOnlyHint", true);
            annotations.addProperty("destructiveHint", false);
            annotations.addProperty("idempotentHint", true);
            annotations.addProperty("openWorldHint", false);
            return this;
        }

        /** Runs the handler on the network thread; it reaches the game through {@link McpContext#onGameThread}. */
        public Builder offGameThread() {
            this.onGameThread = false;
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
