package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.config.ConfigFile;

import java.util.List;

/**
 * The {@code mcp} section of the client settings: the local MCP server is off until switched on there.
 *
 * @author vyrriox
 */
public final class McpConfig {
    public static final String SECTION = "mcp";
    public static final int DEFAULT_PORT = 24464;

    public final ConfigFile.BoolValue enabled;
    public final ConfigFile.IntValue port;
    public final ConfigFile.StringValue token;
    public final ConfigFile.IntValue timeoutSeconds;
    public final ConfigFile.StringListValue allowedOrigins;

    private McpConfig(ConfigFile.Section section) {
        enabled = section.bool("enabled", false,
                "Start the MCP server, so AI assistants and other MCP clients on this computer can read what Just Replay is" + "\n"
                        + "doing: its state, your replays, the open project and its markers. It only listens on 127.0.0.1 (this" + "\n"
                        + "computer, never the network) and every request must carry the token below. Off by default.");
        port = section.integer("port", DEFAULT_PORT, 1024, 65535,
                "Port of the MCP server. Clients connect to http://127.0.0.1:<port>/mcp.");
        token = section.string("token", "",
                "Access token. MCP clients send it in the header 'Authorization: Bearer <token>'. A random one is written here" + "\n"
                        + "when the server first starts; empty this value to get a new one. At least " + McpToken.MIN_LENGTH + " characters" + "\n"
                        + "(letters, digits, - _ . ~ + / =), a shorter one is replaced. Keep it to yourself.");
        timeoutSeconds = section.integer("timeoutSeconds", 10, 1, 120,
                "Longest wait, in seconds, for the game to answer one request (while a world loads, for instance)." + "\n"
                        + "Past it the request fails with an error the client can show.");
        allowedOrigins = section.list("allowedOrigins", List.of(),
                "Web pages allowed to call the server from a browser, as origins such as http://localhost:6274 (the MCP" + "\n"
                        + "Inspector). Desktop and command line clients send no origin and need nothing here.");
    }

    /** Adds the section to {@code spec}, after the sections already there. */
    public static McpConfig define(ConfigFile spec) {
        return new McpConfig(spec.section(SECTION,
                "MCP server (Model Context Protocol): lets AI assistants on this computer read Just Replay."));
    }
}
