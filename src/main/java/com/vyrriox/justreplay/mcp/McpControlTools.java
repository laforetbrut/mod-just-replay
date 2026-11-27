package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The control tools of the MCP server, the ones that drive Just Replay: playback and the view, the edit of the open
 * replay, recordings and exports. They go through the same calls as the editor, the keys and the replay list (see
 * {@link McpControl}), so a change made over MCP is saved, undone and shown exactly like one made by hand. What they
 * may do is limited by the MCP settings before they run (see {@link McpProtocol.Guards}).
 *
 * @author vyrriox
 */
public final class McpControlTools {
    /** The control tools, in the order {@code tools/list} gives them. */
    public static final List<String> TOOLS;

    static {
        List<String> all = new ArrayList<>(McpPlayTools.TOOLS);
        all.addAll(McpEditTools.TOOLS);
        all.addAll(McpRecordTools.TOOLS);
        all.addAll(McpExportTools.TOOLS);
        TOOLS = List.copyOf(all);
    }

    /** Added to the instructions the server gives when a client connects. */
    public static final String INSTRUCTIONS = " To drive it: open_replay and close_replay, set_playback, seek and set_camera play the "
            + "replay; the edit_* and set_* tools change its edit (cameras, keyframes, cuts, speed, actors and their actions, "
            + "audio, effects, overlays, world, entities, markers) exactly as the editor does: each change is saved at once and "
            + "undo takes it back. start_recording, stop_recording and save_instant_replay record; start_export, get_export and "
            + "cancel_export make videos, which never overwrite a file. Positions of cameras are the eye, as in render_frame. "
            + "Deleting files is refused unless the player allows it in the settings.";

    private McpControlTools() {
    }

    /** Adds every control tool to {@code registry}, after the read and observation tools. */
    public static void register(McpRegistry registry, McpGame game, McpObserver observer, McpControl control) {
        McpPlayTools.register(registry, game, observer, control);
        McpEditTools.register(registry, observer, control);
        McpRecordTools.register(registry, game, control);
        McpExportTools.register(registry, observer, control);
    }

    // ------------------------------------------------------------------ shared helpers

    /** Game thread: the open replay, or a message saying how to open one. */
    static McpObserver.Replay replay(McpObserver observer) throws McpArgs.Invalid {
        return McpObserveTools.requireReplay(observer);
    }

    /** Game thread: the open replay when its edit and play head may change now (no export nor frame being drawn). */
    static McpObserver.Replay editable(McpObserver observer) throws McpArgs.Invalid {
        McpObserver.Replay r = replay(observer);
        if (r.exporting()) {
            throw new McpArgs.Invalid("A video export or a frame is being drawn: the replay cannot change until it is done "
                    + "(get_export tells how far it is, cancel_export stops it).");
        }
        return r;
    }

    /** A tick argument checked against the length of the replay, or {@code def} when absent. */
    static int tick(McpArgs args, String name, McpObserver.Replay r, int def) throws McpArgs.Invalid {
        Integer tick = args.optionalInteger(name, 0, McpObserveTools.MAX_TICK);
        if (tick == null) {
            return def;
        }
        checkTick(tick, r.durationTicks(), name);
        return tick;
    }

    /** A tick argument that must be given. */
    static int requiredTick(McpArgs args, String name, McpObserver.Replay r) throws McpArgs.Invalid {
        if (!args.has(name)) {
            throw new McpArgs.Invalid("Argument '" + name + "' is required (a tick, 20 per second, from 0 to " + r.durationTicks() + ")");
        }
        return tick(args, name, r, 0);
    }

    static void checkTick(int tick, int duration, String name) throws McpArgs.Invalid {
        if (tick < 0 || tick > duration) {
            throw new McpArgs.Invalid("'" + name + "' " + tick + " is outside the replay (0 to " + duration + " ticks, "
                    + McpJson.time(duration) + ")");
        }
    }

    /**
     * Ticks of a time written as people read it: {@code 62.5} seconds, {@code 1:02.5} or {@code 1:00:02}, or -1 when
     * it is not one.
     */
    static int parseTime(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim();
        if (!t.matches("(\\d{1,4}:){0,2}\\d{1,6}(\\.\\d{1,3})?")) {
            return -1;
        }
        String[] parts = t.split(":");
        double seconds = Double.parseDouble(parts[parts.length - 1]);
        if (parts.length > 1 && seconds >= 60.0) {
            return -1;
        }
        double minutes = parts.length >= 2 ? Integer.parseInt(parts[parts.length - 2]) : 0;
        if (parts.length == 3 && minutes >= 60) {
            return -1;
        }
        double hours = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
        double total = ((hours * 60.0) + minutes) * 60.0 + seconds;
        return total * 20.0 > McpObserveTools.MAX_TICK ? -1 : (int) Math.round(total * 20.0);
    }

    /** The replay and the play head, the header of every answer about the open replay. */
    static JsonObject header(McpObserver.Replay r) {
        JsonObject out = new JsonObject();
        out.add("replay", McpObserveTools.replayInfo(r, r.tick()));
        return out;
    }

    /** The folder of a saved replay by its id, checked to hold a replay. */
    static Path savedReplay(McpGame game, String id) throws McpArgs.Invalid {
        Path dir = McpReadTools.replayDir(game.replaysRoot(), id);
        if (dir == null || !Files.isRegularFile(dir.resolve(ReplayStorage.STREAM))) {
            throw new McpArgs.Invalid("No saved replay has the id '" + id + "' (ids come from list_replays)");
        }
        return dir;
    }

    /**
     * A marker of the project by its id, or by its exact name (any case) when no id matches and only one marker has
     * that name.
     */
    static Marker marker(ReplayProject project, String key) throws McpArgs.Invalid {
        Marker byId = project.marker(key);
        if (byId != null) {
            return byId;
        }
        Marker found = null;
        for (Marker m : project.markers) {
            if (m.name != null && m.name.equalsIgnoreCase(key)) {
                if (found != null) {
                    throw new McpArgs.Invalid("Several markers are named '" + key + "': use its id (list_markers gives them)");
                }
                found = m;
            }
        }
        if (found == null) {
            throw new McpArgs.Invalid("No marker has the id or name '" + key + "' (list_markers gives them)");
        }
        return found;
    }

    /** Lower case, for messages that list values. */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Waits a little between two looks at the game, off the game thread. */
    static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
