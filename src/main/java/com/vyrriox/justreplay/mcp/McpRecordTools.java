package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import com.vyrriox.justreplay.format.ReplayNames;
import com.vyrriox.justreplay.format.ReplayRename;
import com.vyrriox.justreplay.format.ReplayStorage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The recording tools: start and stop a recording, save the last minutes kept in memory, rename a saved replay and,
 * when the player allowed it in the settings, delete one. They make the calls the keys and the replay list make.
 *
 * @author vyrriox
 */
final class McpRecordTools {
    static final List<String> TOOLS = List.of("start_recording", "stop_recording", "save_instant_replay", "rename_replay", "delete_replay");
    /** Writing the files of a long recording, or of the last minutes, takes a while. */
    static final int WRITE_TIMEOUT_SECONDS = 90;
    private static final String NAME = "A name for the replay (" + ReplayNames.MAX_LENGTH + " characters at most), as the naming "
            + "window gives it; left out, the naming window comes up for the player as when they press the key (when the "
            + "settings ask for it).";

    private McpRecordTools() {
    }

    static void register(McpRegistry registry, McpGame game, McpControl control) {
        registry.add(McpTool.builder("start_recording")
                .title("Start recording")
                .description("Starts recording the world the player is in, as the record key does; with 'minutes' the recording "
                        + "stops by itself, as the timed recording key does. Refused while a replay is open or a recording runs.")
                .input(McpSchema.object()
                        .integer("minutes", "Stops by itself after this many minutes; until stop_recording when left out.", 1, 600))
                .additive()
                .handler((args, context) -> start(game, control, args))
                .build());
        registry.add(McpTool.builder("stop_recording")
                .title("Stop recording")
                .description("Stops the running recording and writes it as a saved replay, as the record key does. With 'name' "
                        + "the replay is named at once (never over another replay: a taken name gets (2)).")
                .input(McpSchema.object().string("name", NAME))
                .additive()
                .offGameThread()
                .timeoutSeconds(WRITE_TIMEOUT_SECONDS)
                .handler((args, context) -> stop(control, args, context))
                .build());
        registry.add(McpTool.builder("save_instant_replay")
                .title("Save the last minutes")
                .description("Saves the last minutes kept in memory as a replay, as the instant replay key does; needs the "
                        + "instant replay setting on (get_state tells). The recording keeps going.")
                .input(McpSchema.object().string("name", NAME))
                .additive()
                .offGameThread()
                .timeoutSeconds(WRITE_TIMEOUT_SECONDS)
                .handler((args, context) -> saveBuffer(control, args, context))
                .build());
        registry.add(McpTool.builder("rename_replay")
                .title("Rename a replay")
                .description("Renames a saved replay, as the replay list does: its name and, when it can, its folder, so its id "
                        + "may change (the answer gives the new one). Never over another replay. Refused while it is open, "
                        + "recorded or being renamed.")
                .input(McpSchema.object()
                        .string("replay", "Id of the saved replay (list_replays gives them).")
                        .string("name", "The new name, " + ReplayNames.MAX_LENGTH + " characters at most.")
                        .required("replay", "name"))
                .destructive()
                .offGameThread()
                .timeoutSeconds(WRITE_TIMEOUT_SECONDS)
                .handler((args, context) -> rename(game, control, args, context))
                .build());
        registry.add(McpTool.builder("delete_replay")
                .title("Delete a replay")
                .description("Deletes a saved replay for good, its recording and its edit, as the replay list does. Refused unless "
                        + "the player switched on 'Allow deleting files' in the MCP settings, and while the replay is open or "
                        + "recorded. There is no undo.")
                .input(McpSchema.object()
                        .string("replay", "Id of the saved replay (list_replays gives them).")
                        .required("replay"))
                .deletesFiles()
                .offGameThread()
                .timeoutSeconds(WRITE_TIMEOUT_SECONDS)
                .handler((args, context) -> delete(game, control, args, context))
                .build());
    }

    static McpToolResult start(McpGame game, McpControl control, McpArgs args) throws McpArgs.Invalid {
        Integer minutes = args.optionalInteger("minutes", 1, 600);
        String error = control.startRecording(minutes == null ? 0 : minutes * 1200);
        if (error != null) {
            throw new McpArgs.Invalid(error);
        }
        McpState s = game.state();
        JsonObject out = new JsonObject();
        out.addProperty("recording", s.recording.active);
        out.addProperty("replayId", s.recording.replayId);
        if (minutes != null) {
            out.addProperty("stopsAfterMinutes", minutes);
        }
        return McpToolResult.of(out);
    }

    /** The {@code name} argument, cleaned as the naming window cleans it, or null when absent. */
    private static String name(McpArgs args, boolean required) throws McpArgs.Invalid {
        String typed = required ? args.required("name") : args.string("name", null);
        if (typed == null) {
            return null;
        }
        if (typed.length() > ReplayNames.MAX_LENGTH) {
            throw new McpArgs.Invalid("Argument 'name' takes at most " + ReplayNames.MAX_LENGTH + " characters");
        }
        String name = ReplayNames.clean(typed);
        if (name.isEmpty()) {
            throw new McpArgs.Invalid("Argument 'name' has nothing left once cleaned: give letters or digits");
        }
        return name;
    }

    static McpToolResult stop(McpControl control, McpArgs args, McpContext context) throws Exception {
        String name = name(args, false);
        McpControl.Stopped stopped = context.onGameThread(() -> control.stopRecording(name == null));
        if (stopped == null || stopped.dir() == null) {
            throw new McpArgs.Invalid("No recording is running (get_state tells what the mod is doing)");
        }
        JsonObject out = new JsonObject();
        out.addProperty("stopped", true);
        out.addProperty("incomplete", stopped.incomplete());
        Path dir = stopped.dir();
        if (name != null && stopped.incomplete()) {
            out.addProperty("message", "Some files of the recording could not be written, so it was not renamed.");
        } else if (name != null) {
            ReplayRename.Result result = wait(control.rename(dir, name), context, "renaming the replay");
            dir = result.dir();
            out.addProperty("renamed", result.status() != ReplayRename.Status.UNCHANGED);
        }
        describe(out, dir);
        return McpToolResult.of(out);
    }

    static McpToolResult saveBuffer(McpControl control, McpArgs args, McpContext context) throws Exception {
        String name = name(args, false);
        CompletableFuture<Path> saving = context.onGameThread(() -> control.saveInstantReplay(name == null));
        if (saving == null) {
            throw new McpArgs.Invalid("The last minutes are not kept now: the instant replay setting is off, or no world is "
                    + "loaded (get_state tells)");
        }
        Path dir = wait(saving, context, "writing the last minutes");
        JsonObject out = new JsonObject();
        out.addProperty("saved", true);
        if (name != null) {
            ReplayRename.Result result = wait(control.rename(dir, name), context, "renaming the replay");
            dir = result.dir();
            out.addProperty("renamed", result.status() != ReplayRename.Status.UNCHANGED);
        }
        describe(out, dir);
        return McpToolResult.of(out);
    }

    static McpToolResult rename(McpGame game, McpControl control, McpArgs args, McpContext context) throws Exception {
        Path dir = McpControlTools.savedReplay(game, args.required("replay"));
        String name = name(args, true);
        if (context.onGameThread(() -> control.busy(dir))) {
            throw new McpArgs.Invalid("Replay '" + dir.getFileName() + "' is open, being recorded or renamed: it can be renamed "
                    + "once that is over (close_replay closes it)");
        }
        String before = dir.getFileName().toString();
        ReplayRename.Result result = wait(control.rename(dir, name), context, "renaming the replay");
        JsonObject out = new JsonObject();
        out.addProperty("previousId", before);
        out.addProperty("status", result.status().name());
        out.addProperty("renamed", result.status() != ReplayRename.Status.UNCHANGED);
        describe(out, result.dir());
        if (result.status() == ReplayRename.Status.UNCHANGED) {
            out.addProperty("message", "Nothing changed: the replay already has that name.");
        }
        return McpToolResult.of(out);
    }

    static McpToolResult delete(McpGame game, McpControl control, McpArgs args, McpContext context) throws Exception {
        Path dir = McpControlTools.savedReplay(game, args.required("replay"));
        if (context.onGameThread(() -> control.busy(dir))) {
            throw new McpArgs.Invalid("Replay '" + dir.getFileName() + "' is open, being recorded or renamed: it cannot be deleted now");
        }
        String name = ReplayStorage.readMeta(dir).name;
        ReplayStorage.deleteRecursively(dir);
        JsonObject out = new JsonObject();
        out.addProperty("deleted", dir.getFileName().toString());
        out.addProperty("name", name == null ? "" : name);
        return McpToolResult.of(out);
    }

    private static void describe(JsonObject out, Path dir) {
        out.addProperty("replayId", dir.getFileName().toString());
        String name = ReplayStorage.readMeta(dir).name;
        out.addProperty("name", name == null ? "" : name);
    }

    /** Waits off the game thread for the game to finish some work, within the time the request has left. */
    static <T> T wait(CompletableFuture<T> future, McpContext context, String what) throws Exception {
        try {
            return future.get(Math.max(1L, context.remainingMillis() - 500L), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new McpArgs.Invalid("The game failed " + what + ": " + McpProtocol.describe(cause));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new McpArgs.Invalid("The game is still " + what + ": get_state and list_replays tell when it is done");
        }
    }
}
