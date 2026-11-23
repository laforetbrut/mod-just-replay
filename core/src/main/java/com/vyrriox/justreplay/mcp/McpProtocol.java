package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.vyrriox.justreplay.JustReplayCore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The JSON-RPC side of the MCP server: reads one message, checks it and answers it with the tools and resources of
 * a {@link McpRegistry}. It speaks both eras of the specification on the same endpoint:
 * <ul>
 *     <li>modern versions ({@code 2026-07-28}): no handshake, every request names its version in
 *     {@code _meta} and in the {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name} headers,
 *     results carry {@code resultType}, {@code server/discover} tells what the server supports;</li>
 *     <li>legacy versions ({@code 2025-03-26} to {@code 2025-11-25}): an {@code initialize} handshake, then plain
 *     requests. No session is created, so every request stands alone in both eras.</li>
 * </ul>
 * Tools and resources that need the game run on the game thread through a {@link GameThreadQueue}; nothing here
 * touches the game.
 *
 * @author vyrriox
 */
public final class McpProtocol {
    public static final String LATEST = "2026-07-28";
    public static final List<String> MODERN = List.of("2026-07-28");
    public static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    /** Version of a legacy request without an {@code MCP-Protocol-Version} header, as the specification says. */
    public static final String LEGACY_DEFAULT = "2025-03-26";
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    /** Lists do not change while the server runs: clients may keep them five minutes. */
    static final long LIST_TTL_MILLIS = 300_000L;
    private static final String BASE64_START = "=?base64?";
    private static final String BASE64_END = "?=";

    /** Who the server is: shown by clients, and the instructions given to the model. */
    public record Info(String name, String title, String version, String instructions) {
    }

    /**
     * The settings that limit what tools may do, read at every call: a read only server refuses every tool that
     * changes something, and tools that delete files need the player's explicit permission.
     */
    public interface Guards {
        /** Changes allowed, deleting files refused: the defaults of the settings. */
        Guards DEFAULTS = of(() -> false, () -> false);

        boolean readOnly();

        boolean allowDeleting();

        static Guards of(java.util.function.BooleanSupplier readOnly, java.util.function.BooleanSupplier allowDeleting) {
            return new Guards() {
                @Override
                public boolean readOnly() {
                    return readOnly.getAsBoolean();
                }

                @Override
                public boolean allowDeleting() {
                    return allowDeleting.getAsBoolean();
                }
            };
        }
    }

    /** The HTTP answer to one message: a status and a JSON body, or no body ({@code 202 Accepted}). */
    public record Reply(int status, JsonObject body) {
        static final Reply ACCEPTED = new Reply(202, null);
    }

    private final McpRegistry registry;
    private final GameThreadQueue queue;
    private final Info info;
    private final LongSupplier timeoutMillis;
    private final Guards guards;
    private final AtomicLong calls = new AtomicLong();
    private volatile long lastCallMillis;

    public McpProtocol(McpRegistry registry, GameThreadQueue queue, Info info, LongSupplier timeoutMillis) {
        this(registry, queue, info, timeoutMillis, Guards.DEFAULTS);
    }

    public McpProtocol(McpRegistry registry, GameThreadQueue queue, Info info, LongSupplier timeoutMillis, Guards guards) {
        this.registry = registry;
        this.queue = queue;
        this.info = info;
        this.timeoutMillis = timeoutMillis;
        this.guards = guards;
    }

    /**
     * Why the settings refuse a tool now, or null when it may run. Checked before its arguments are read, so a refused
     * call changes nothing whatever it asks.
     */
    static String refusal(McpTool tool, Guards guards) {
        if (tool.access() == McpTool.Access.READ) {
            return null;
        }
        if (guards.readOnly()) {
            return "The MCP server of Just Replay is read only: the player switched on 'Read only' (mcp.readOnly) in the MCP "
                    + "settings, so " + tool.name() + " is refused and nothing changed. The read and observation tools still work.";
        }
        if (tool.access() == McpTool.Access.DELETE && !guards.allowDeleting()) {
            return "Deleting files over MCP is off: " + tool.name() + " deletes files for good, so it only works once the player "
                    + "switches on 'Allow deleting files' (mcp.allowDestructive) in the MCP settings. Nothing was deleted.";
        }
        return null;
    }

    /** Every version this server answers, newest first. */
    public static List<String> supportedVersions() {
        List<String> all = new ArrayList<>(MODERN);
        all.addAll(LEGACY);
        return all;
    }

    /** Tool calls and resource reads answered so far. */
    public long calls() {
        return calls.get();
    }

    /** {@link System#currentTimeMillis()} of the last tool call or resource read, 0 before the first. */
    public long lastCallMillis() {
        return lastCallMillis;
    }

    /**
     * Answers the body of one HTTP POST.
     *
     * @param headers the request's header of a name, matched without case, or null when absent
     */
    public Reply handle(String body, Function<String, String> headers) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body == null ? "" : body);
        } catch (JsonParseException | IllegalStateException e) {
            return error(null, new McpException(McpException.PARSE_ERROR, "Parse error: the body is not valid JSON", 400));
        }
        if (parsed.isJsonArray()) {
            return error(null, new McpException(McpException.INVALID_REQUEST,
                    "Batch requests are not supported: send one JSON-RPC message per request", 400));
        }
        if (!parsed.isJsonObject()) {
            return error(null, new McpException(McpException.INVALID_REQUEST, "A JSON-RPC message is a JSON object", 400));
        }
        JsonObject message = parsed.getAsJsonObject();
        JsonElement id = message.get("id");
        JsonElement replyId = isRequestId(id) ? id : null;
        if (!message.has("method")) {
            if (message.has("result") || message.has("error")) {
                // A response, though this server never asks the client anything: nothing waits for it.
                return Reply.ACCEPTED;
            }
            return error(replyId, new McpException(McpException.INVALID_REQUEST, "The message has no method", 400));
        }
        if (!isString(message.get("jsonrpc")) || !"2.0".equals(message.get("jsonrpc").getAsString())) {
            return error(replyId, new McpException(McpException.INVALID_REQUEST, "jsonrpc must be \"2.0\"", 400));
        }
        if (!isString(message.get("method"))) {
            return error(replyId, new McpException(McpException.INVALID_REQUEST, "method must be a string", 400));
        }
        String method = message.get("method").getAsString();
        if (id == null) {
            // A notification (notifications/initialized, a cancellation): accepted, nothing to answer.
            return Reply.ACCEPTED;
        }
        if (replyId == null) {
            return error(null, new McpException(McpException.INVALID_REQUEST, "id must be a string or a number", 400));
        }
        JsonElement p = message.get("params");
        if (p != null && !p.isJsonNull() && !p.isJsonObject()) {
            return error(id, new McpException(McpException.INVALID_PARAMS, "params must be an object", 400));
        }
        JsonObject params = p == null || p.isJsonNull() ? new JsonObject() : p.getAsJsonObject();
        try {
            String metaVersion = metaVersion(params);
            String headerVersion = trimmed(headers.apply("MCP-Protocol-Version"));
            if (!"initialize".equals(method)) {
                if (metaVersion != null) {
                    return result(id, modern(method, params, metaVersion, headers));
                }
                if (headerVersion != null && MODERN.contains(headerVersion)) {
                    throw new McpException(McpException.INVALID_PARAMS, "Missing _meta." + META_PROTOCOL_VERSION
                            + ": requests of protocol version " + headerVersion + " carry it in params._meta", 400);
                }
                if (headerVersion != null && !LEGACY.contains(headerVersion)) {
                    throw unsupported(headerVersion);
                }
            }
            return result(id, legacy(method, params, headerVersion == null ? LEGACY_DEFAULT : headerVersion));
        } catch (McpException e) {
            return error(id, e);
        }
    }

    // ------------------------------------------------------------------ eras

    private JsonObject modern(String method, JsonObject params, String version, Function<String, String> headers)
            throws McpException {
        if (!MODERN.contains(version)) {
            throw unsupported(version);
        }
        String headerVersion = trimmed(headers.apply("MCP-Protocol-Version"));
        if (headerVersion == null) {
            throw headerMismatch("the MCP-Protocol-Version header is missing");
        }
        if (!headerVersion.equals(version)) {
            throw headerMismatch("MCP-Protocol-Version header value '" + headerVersion + "' does not match body value '" + version + "'");
        }
        String headerMethod = trimmed(headers.apply("Mcp-Method"));
        if (headerMethod == null) {
            throw headerMismatch("the Mcp-Method header is missing");
        }
        if (!headerMethod.equals(method)) {
            throw headerMismatch("Mcp-Method header value '" + headerMethod + "' does not match body value '" + method + "'");
        }
        String nameField = switch (method) {
            case "tools/call", "prompts/get" -> "name";
            case "resources/read" -> "uri";
            default -> null;
        };
        if (nameField != null && isString(params.get(nameField))) {
            String raw = headers.apply("Mcp-Name");
            if (raw == null) {
                throw headerMismatch("the Mcp-Name header is missing");
            }
            String name = decodeHeader(raw);
            String body = params.get(nameField).getAsString();
            if (!body.equals(name)) {
                throw headerMismatch("Mcp-Name header value '" + name + "' does not match body value '" + body + "'");
            }
        }
        JsonElement capabilities = params.getAsJsonObject("_meta").get(META_CLIENT_CAPABILITIES);
        if (capabilities == null || !capabilities.isJsonObject()) {
            throw new McpException(McpException.INVALID_PARAMS, "Missing _meta." + META_CLIENT_CAPABILITIES, 400);
        }
        JsonObject result;
        long ttl = -1;
        switch (method) {
            case "server/discover" -> {
                result = new JsonObject();
                JsonArray versions = new JsonArray();
                supportedVersions().forEach(versions::add);
                result.add("supportedVersions", versions);
                result.add("capabilities", capabilities());
                if (info.instructions() != null) {
                    result.addProperty("instructions", info.instructions());
                }
                ttl = LIST_TTL_MILLIS;
            }
            case "tools/list" -> {
                result = toolsList(params);
                ttl = LIST_TTL_MILLIS;
            }
            case "tools/call" -> result = toolsCall(params, version);
            case "resources/list" -> {
                result = resourcesList(params);
                ttl = LIST_TTL_MILLIS;
            }
            case "resources/templates/list" -> {
                result = templatesList(params);
                ttl = LIST_TTL_MILLIS;
            }
            case "resources/read" -> {
                result = resourcesRead(params, version, true);
                ttl = 0;
            }
            default -> throw new McpException(McpException.METHOD_NOT_FOUND, "Method not found: " + method, 404);
        }
        result.addProperty("resultType", "complete");
        if (ttl >= 0) {
            result.addProperty("ttlMs", ttl);
            result.addProperty("cacheScope", "private");
        }
        JsonObject meta = new JsonObject();
        meta.add(META_SERVER_INFO, implementation(false));
        result.add("_meta", meta);
        return result;
    }

    private JsonObject legacy(String method, JsonObject params, String version) throws McpException {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> new JsonObject();
            case "tools/list" -> toolsList(params);
            case "tools/call" -> toolsCall(params, version);
            case "resources/list" -> resourcesList(params);
            case "resources/templates/list" -> templatesList(params);
            case "resources/read" -> resourcesRead(params, version, false);
            default -> throw new McpException(McpException.METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    private JsonObject initialize(JsonObject params) {
        JsonElement requested = params.get("protocolVersion");
        String asked = isString(requested) ? requested.getAsString().trim() : "";
        JsonObject result = new JsonObject();
        // The client's version when this server speaks it, its newest legacy one otherwise: the client then decides.
        result.addProperty("protocolVersion", LEGACY.contains(asked) ? asked : LEGACY.get(0));
        result.add("capabilities", capabilities());
        result.add("serverInfo", implementation(true));
        if (info.instructions() != null) {
            result.addProperty("instructions", info.instructions());
        }
        return result;
    }

    // ------------------------------------------------------------------ methods

    private JsonObject toolsList(JsonObject params) throws McpException {
        checkCursor(params);
        JsonArray tools = new JsonArray();
        for (McpTool tool : registry.tools()) {
            tools.add(tool.describe());
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolsCall(JsonObject params, String version) throws McpException {
        JsonElement name = params.get("name");
        if (!isString(name)) {
            throw new McpException(McpException.INVALID_PARAMS, "tools/call needs the name of a tool", 400);
        }
        McpTool tool = registry.tool(name.getAsString());
        if (tool == null) {
            JsonObject data = new JsonObject();
            JsonArray known = new JsonArray();
            registry.tools().forEach(t -> known.add(t.name()));
            data.add("tools", known);
            throw new McpException(McpException.INVALID_PARAMS, "Unknown tool: " + name.getAsString(), 200, data);
        }
        JsonElement arguments = params.get("arguments");
        if (arguments != null && !arguments.isJsonNull() && !arguments.isJsonObject()) {
            throw new McpException(McpException.INVALID_PARAMS, "arguments must be an object", 400);
        }
        McpArgs args = new McpArgs(arguments == null || arguments.isJsonNull() ? null : arguments.getAsJsonObject());
        McpContext context = context(version, tool.minTimeoutMillis());
        markCall();
        return runTool(tool, args, context).toJson();
    }

    private McpToolResult runTool(McpTool tool, McpArgs args, McpContext context) {
        String refused = refusal(tool, guards);
        if (refused != null) {
            return McpToolResult.error(refused);
        }
        try {
            args.allowOnly(tool.argumentNames());
            McpToolResult result = tool.onGameThread()
                    ? queue.call(() -> tool.handler().call(args, context), context.remainingMillis())
                    : tool.handler().call(args, context);
            return result == null ? McpToolResult.error("The tool " + tool.name() + " gave no answer") : result;
        } catch (McpArgs.Invalid | GameThreadQueue.Unavailable e) {
            return McpToolResult.error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpToolResult.error("The call was interrupted");
        } catch (Exception | LinkageError e) {
            JustReplayCore.LOGGER.warn("MCP tool {} failed", tool.name(), e);
            return McpToolResult.error("The tool " + tool.name() + " failed: " + describe(e));
        }
    }

    private JsonObject resourcesList(JsonObject params) throws McpException {
        checkCursor(params);
        JsonArray resources = new JsonArray();
        for (McpResource resource : registry.resources()) {
            resources.add(resource.describe());
        }
        JsonObject result = new JsonObject();
        result.add("resources", resources);
        return result;
    }

    private JsonObject templatesList(JsonObject params) throws McpException {
        checkCursor(params);
        JsonArray templates = new JsonArray();
        for (McpResource.Template template : registry.templates()) {
            templates.add(template.describe());
        }
        JsonObject result = new JsonObject();
        result.add("resourceTemplates", templates);
        return result;
    }

    private JsonObject resourcesRead(JsonObject params, String version, boolean modern) throws McpException {
        JsonElement uriElement = params.get("uri");
        if (!isString(uriElement)) {
            throw new McpException(McpException.INVALID_PARAMS, "resources/read needs a uri", 400);
        }
        String uri = uriElement.getAsString();
        McpContext context = context(version);
        markCall();
        String text = null;
        String mimeType = null;
        try {
            McpResource resource = registry.resource(uri);
            if (resource != null) {
                text = resource.onGameThread()
                        ? queue.call(() -> resource.reader().read(context), context.remainingMillis())
                        : resource.reader().read(context);
                mimeType = resource.mimeType();
            } else {
                for (McpResource.Template template : registry.templates()) {
                    String value = template.match(uri);
                    if (value == null) {
                        continue;
                    }
                    text = template.onGameThread()
                            ? queue.call(() -> template.reader().read(value, context), context.remainingMillis())
                            : template.reader().read(value, context);
                    if (text != null) {
                        mimeType = template.mimeType();
                        break;
                    }
                }
            }
        } catch (GameThreadQueue.Unavailable e) {
            throw new McpException(McpException.INTERNAL_ERROR, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpException.INTERNAL_ERROR, "The read was interrupted");
        } catch (Exception | LinkageError e) {
            JustReplayCore.LOGGER.warn("MCP resource {} failed", uri, e);
            throw new McpException(McpException.INTERNAL_ERROR, "Reading " + uri + " failed: " + describe(e));
        }
        if (text == null) {
            JsonObject data = new JsonObject();
            data.addProperty("uri", uri);
            throw new McpException(modern ? McpException.INVALID_PARAMS : McpException.RESOURCE_NOT_FOUND_LEGACY,
                    "Resource not found: " + uri, 200, data);
        }
        JsonObject content = new JsonObject();
        content.addProperty("uri", uri);
        content.addProperty("mimeType", mimeType);
        content.addProperty("text", text);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private McpContext context(String version) {
        return context(version, 0L);
    }

    /** A context whose deadline is the settings' timeout, or {@code minMillis} when that is longer. */
    private McpContext context(String version, long minMillis) {
        long timeout = Math.max(1L, Math.max(minMillis, timeoutMillis.getAsLong()));
        return new McpContext(queue, System.nanoTime() + timeout * 1_000_000L, version);
    }

    private void markCall() {
        calls.incrementAndGet();
        lastCallMillis = System.currentTimeMillis();
    }

    private static JsonObject capabilities() {
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        JsonObject resources = new JsonObject();
        resources.addProperty("subscribe", false);
        resources.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", tools);
        capabilities.add("resources", resources);
        return capabilities;
    }

    private JsonObject implementation(boolean withTitle) {
        JsonObject o = new JsonObject();
        o.addProperty("name", info.name());
        if (withTitle && info.title() != null) {
            o.addProperty("title", info.title());
        }
        o.addProperty("version", info.version() == null || info.version().isEmpty() ? "unknown" : info.version());
        return o;
    }

    /** The version a modern request names in its {@code _meta}, or null for a legacy request. */
    private static String metaVersion(JsonObject params) throws McpException {
        JsonElement meta = params.get("_meta");
        if (meta == null || !meta.isJsonObject()) {
            return null;
        }
        JsonElement version = meta.getAsJsonObject().get(META_PROTOCOL_VERSION);
        if (version == null || version.isJsonNull()) {
            return null;
        }
        if (!isString(version)) {
            throw new McpException(McpException.INVALID_PARAMS, "_meta." + META_PROTOCOL_VERSION + " must be a string", 400);
        }
        return version.getAsString().trim();
    }

    private static McpException unsupported(String requested) {
        JsonObject data = new JsonObject();
        JsonArray supported = new JsonArray();
        supportedVersions().forEach(supported::add);
        data.add("supported", supported);
        data.addProperty("requested", requested);
        return new McpException(McpException.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", 400, data);
    }

    private static McpException headerMismatch(String what) {
        return new McpException(McpException.HEADER_MISMATCH, "Header mismatch: " + what, 400);
    }

    /** A header value in the {@code =?base64?...?=} form decoded, any other value as it is. */
    static String decodeHeader(String raw) throws McpException {
        String value = raw.trim();
        if (value.startsWith(BASE64_START) && value.endsWith(BASE64_END) && value.length() >= BASE64_START.length() + BASE64_END.length()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(value.substring(BASE64_START.length(), value.length() - BASE64_END.length()));
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw headerMismatch("a header holds invalid Base64");
            }
        }
        return value;
    }

    private static void checkCursor(JsonObject params) throws McpException {
        JsonElement cursor = params.get("cursor");
        if (cursor != null && !cursor.isJsonNull() && !isString(cursor)) {
            throw new McpException(McpException.INVALID_PARAMS, "cursor must be a string", 400);
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isRequestId(JsonElement id) {
        if (id == null || !id.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = id.getAsJsonPrimitive();
        return p.isString() || p.isNumber();
    }

    static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static Reply result(JsonElement id, JsonObject result) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.add("id", id);
        body.add("result", result);
        return new Reply(200, body);
    }

    /** An error answer; {@code id} null leaves it out, as for a message whose id could not be read. */
    static Reply error(JsonElement id, McpException e) {
        JsonObject error = new JsonObject();
        error.addProperty("code", e.code());
        error.addProperty("message", e.getMessage());
        if (e.data() != null) {
            error.add("data", e.data());
        }
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        if (id != null) {
            body.add("id", id);
        }
        body.add("error", error);
        return new Reply(e.httpStatus(), body);
    }
}
