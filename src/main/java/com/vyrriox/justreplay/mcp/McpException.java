package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonElement;

/**
 * A JSON-RPC error answered instead of a result: its code, its message, optional data and the HTTP status the
 * Streamable HTTP transport sends with it.
 *
 * @author vyrriox
 */
public final class McpException extends Exception {
    private static final long serialVersionUID = 1L;

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    /** Resource not found up to protocol version 2025-11-25; later versions answer {@link #INVALID_PARAMS}. */
    public static final int RESOURCE_NOT_FOUND_LEGACY = -32002;
    /** The MCP headers of an HTTP request are missing or do not match its body. */
    public static final int HEADER_MISMATCH = -32020;
    public static final int MISSING_CLIENT_CAPABILITY = -32021;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private final int code;
    private final int httpStatus;
    private final transient JsonElement data;

    /** An error sent with HTTP 200, as JSON-RPC errors usually are. */
    public McpException(int code, String message) {
        this(code, message, 200, null);
    }

    public McpException(int code, String message, int httpStatus) {
        this(code, message, httpStatus, null);
    }

    public McpException(int code, String message, int httpStatus, JsonElement data) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Extra details of the error, or null. */
    public JsonElement data() {
        return data;
    }
}
