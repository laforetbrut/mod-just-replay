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
    public final ConfigFile.BoolValue readOnly;
    public final ConfigFile.BoolValue allowDestructive;

    private McpConfig(ConfigFile.Section section) {
        enabled = section.bool("enabled", false,
                "Start the MCP server, so AI assistants and other MCP clients on this computer can see and drive Just Replay:" + "\n"
                        + "read your replays, the open project and its markers, play and edit the replay, record and export. It only" + "\n"
                        + "listens on 127.0.0.1 (this computer, never the network) and every request must carry the token below." + "\n"
                        + "Off by default.");
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
        readOnly = section.bool("readOnly", false,
                "Only let MCP clients look: every tool that changes something (playback, the edit, recordings, exports) is" + "\n"
                        + "refused, the read and observation tools keep working.");
        allowDestructive = section.bool("allowDestructive", false,
                "Let MCP clients delete saved replays and exported videos for good. Off by default: such calls are refused" + "\n"
                        + "and nothing is deleted. Changes to the edit of a replay stay undoable either way.");
    }

    /** Adds the section to {@code spec}, after the sections already there. */
    public static McpConfig define(ConfigFile spec) {
        return new McpConfig(spec.section(SECTION,
                "MCP server (Model Context Protocol): lets AI assistants on this computer see and drive Just Replay."));
    }
}
