package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.Marker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The playback tools: open and close a replay, play, pause, change the speed, move the play head, and place the
 * view (the free camera with its field of view, the camera path, or an entity viewed through).
 *
 * @author vyrriox
 */
public final class McpPlayTools {
    public static final List<String> TOOLS = List.of("open_replay", "close_replay", "set_playback", "seek", "set_camera");
    public static final List<String> VIEWS = List.of("free", "path", "entity");
    /** Opening a long replay reads and unpacks it first. */
    public static final int OPEN_TIMEOUT_SECONDS = 120;
    public static final int CLOSE_TIMEOUT_SECONDS = 60;
    private static final long POLL_MILLIS = 200L;

    private McpPlayTools() {
    }

    static void register(McpRegistry registry, McpGame game, McpObserver observer, McpControl control) {
        registry.add(McpTool.builder("open_replay")
                .title("Open a replay")
                .description("Opens a saved replay for watching and editing, as the replay list does, and waits until its world "
                        + "is shown (a few seconds, longer for a long replay). A replay already open is closed first, its edit "
                        + "saved. Refused while the player is in a world of their own (they leave it first), during a recording "
                        + "or an export. When the replay was recorded with other mods, the game asks the player first and the "
                        + "answer says so.")
                .input(McpSchema.object()
                        .string("replay", "Id of the saved replay (the 'id' field of list_replays).")
                        .bool("wait", "Wait until the replay is shown; false answers as soon as it starts loading.", true)
                        .required("replay"))
                .additive()
                .offGameThread()
                .timeoutSeconds(OPEN_TIMEOUT_SECONDS)
                .handler((args, context) -> open(game, observer, control, args, context))
                .build());
        registry.add(McpTool.builder("close_replay")
                .title("Close the replay")
                .description("Closes the open replay and goes back to the title screen, as leaving it from the pause menu does; "
                        + "its edit is saved first. Refused while a video export runs (cancel_export stops it) and while the "
                        + "replay's resource packs are still loading, just after it opened.")
                .additive()
                .offGameThread()
                .timeoutSeconds(CLOSE_TIMEOUT_SECONDS)
                .handler((args, context) -> close(observer, control, context))
                .build());
        registry.add(McpTool.builder("set_playback")
                .title("Play, pause, speed")
                .description("Plays or pauses the open replay and sets its speed (one of the steps of the speed keys: 0.1, 0.25, "
                        + "0.5, 1, 2, 4 or 8). 'until' plays from the play head and pauses on that tick, the world moving on the "
                        + "way. The speed changes of the edit (edit_speed) apply on top while playing.")
                .input(McpSchema.object()
                        .bool("playing", "true plays, false pauses.")
                        .number("speed", "Playback speed, 1 is real time.", 0.1, 8.0)
                        .integer("until", "Plays from the play head and pauses on this tick.", 0, McpObserveTools.MAX_TICK))
                .additive()
                .idempotent()
                .handler((args, context) -> playback(observer, control, args))
                .build());
        registry.add(McpTool.builder("seek")
                .title("Move the play head")
                .description("Moves the play head of the open replay to a tick, a time, a marker (its start) or by some ticks, "
                        + "as the timeline does; it stays paused or playing. Give exactly one of tick, time, marker and by.")
                .input(McpSchema.object()
                        .integer("tick", "Tick to go to (20 per second).", 0, McpObserveTools.MAX_TICK)
                        .string("time", "Time to go to, such as 1:02.5, 62.5 (seconds) or 1:00:02.")
                        .string("marker", "Id of a marker (list_markers gives them), or its exact name when no other marker has it.")
                        .integer("by", "Ticks to move from the play head, negative to go back.", -McpObserveTools.MAX_TICK,
                                McpObserveTools.MAX_TICK))
                .additive()
                .handler((args, context) -> seek(observer, args))
                .build());
        registry.add(McpTool.builder("set_camera")
                .title("Place the view")
                .description("Sets what the window shows in the open replay. view=free puts the free camera at an eye position "
                        + "with angles, roll and field of view (fields left out keep the current view; lookAt or lookAtEntity "
                        + "aim it); view=path lets the project's camera path drive the view, as the camera path key does; "
                        + "view=entity views through an entity, as spectating does. The free camera's roll and field of view "
                        + "hold until the path or an entity takes over the view, or the replay closes. Nothing is added to the "
                        + "edit: edit_camera_keyframe does that.")
                .input(McpSchema.object()
                        .choice("view", "free, path or entity. free when x, y, z, angles or lookAt are given.", null,
                                VIEWS.toArray(new String[0]))
                        .string("entity", "view=entity: the entity to view through (list_entities gives the ids).")
                        .number("x", "Eye X of the free camera.", -McpObserveTools.MAX_COORDINATE, McpObserveTools.MAX_COORDINATE)
                        .number("y", "Eye Y of the free camera.", -McpObserveTools.MAX_COORDINATE, McpObserveTools.MAX_COORDINATE)
                        .number("z", "Eye Z of the free camera.", -McpObserveTools.MAX_COORDINATE, McpObserveTools.MAX_COORDINATE)
                        .number("yaw", "Yaw in degrees: 0 looks south (+Z), 90 west (-X), 180 north, -90 east.", -3600.0, 3600.0)
                        .number("pitch", "Pitch in degrees: positive looks down, 90 straight down.", -90.0, 90.0)
                        .number("roll", "Roll in degrees.", -180.0, 180.0)
                        .number("fov", "Vertical field of view in degrees.", McpCamera.MIN_FOV, McpCamera.MAX_FOV)
                        .point("lookAt", "Aims the camera at this point [x, y, z] (replaces yaw and pitch).")
                        .string("lookAtEntity", "Aims the camera at the eyes of this entity (replaces yaw and pitch)."))
                .additive()
                .idempotent()
                .handler((args, context) -> camera(observer, control, args))
                .build());
    }

    // ------------------------------------------------------------------ open and close

    private record Look(McpObserver.Replay replay, McpControl.Opening opening) {
    }

    static McpToolResult open(McpGame game, McpObserver observer, McpControl control, McpArgs args, McpContext context) throws Exception {
        Path dir = McpControlTools.savedReplay(game, args.required("replay"));
        boolean wait = args.bool("wait", true);
        String wanted = dir.getFileName().toString();
        String error = context.onGameThread(() -> {
            McpObserver.Replay r = observer.replay();
            if (r != null && r.id().equals(wanted)) {
                return "";
            }
            return control.openReplay(dir);
        });
        if (error != null && !error.isEmpty()) {
            throw new McpArgs.Invalid(error);
        }
        boolean alreadyOpen = error != null;
        while (true) {
            Look look = context.onGameThread(() -> {
                // The opening first: once it says done, the replay it opened is there.
                McpControl.Opening opening = control.opening();
                return new Look(observer.replay(), opening);
            });
            if (look.replay() != null && look.replay().id().equals(wanted) && look.opening() == McpControl.Opening.NONE) {
                JsonObject out = McpControlTools.header(look.replay());
                out.addProperty("open", true);
                out.addProperty("alreadyOpen", alreadyOpen);
                out.addProperty("paused", look.replay().paused());
                out.addProperty("markers", look.replay().project().markers.size());
                return McpToolResult.of(out);
            }
            if (look.opening() == McpControl.Opening.MODS_WARNING) {
                JsonObject out = waiting(wanted, "mods");
                out.addProperty("message", "The replay was recorded with other mods: the game asks the player whether to open it "
                        + "anyway. Ask them to answer, then get_state tells when it is open.");
                return McpToolResult.of(out);
            }
            if (look.opening() == McpControl.Opening.NONE && look.replay() == null) {
                throw new McpArgs.Invalid("Replay '" + wanted + "' did not open: the game could not load it (its toast and the log "
                        + "say why, a recording of another Minecraft version for instance).");
            }
            if (!wait) {
                JsonObject out = waiting(wanted, look.opening() == McpControl.Opening.PENDING ? "closing" : "loading");
                out.addProperty("message", "The replay is opening: get_state tells when it is open.");
                return McpToolResult.of(out);
            }
            if (context.remainingMillis() < 1500L) {
                JsonObject out = waiting(wanted, "loading");
                out.addProperty("message", "The replay is still loading: get_state tells when it is open.");
                return McpToolResult.of(out);
            }
            McpControlTools.pause(POLL_MILLIS);
        }
    }

    private static JsonObject waiting(String id, String state) {
        JsonObject out = new JsonObject();
        out.addProperty("open", false);
        out.addProperty("replayId", id);
        out.addProperty("waitingFor", state);
        return out;
    }

    static McpToolResult close(McpObserver observer, McpControl control, McpContext context) throws Exception {
        String closed = context.onGameThread(() -> {
            McpObserver.Replay r = McpControlTools.replay(observer);
            if (r.exporting()) {
                throw new McpArgs.Invalid("A video export or a frame is being drawn: cancel_export stops the export first.");
            }
            String error = control.closeReplay();
            if (error != null) {
                throw new McpArgs.Invalid(error);
            }
            return r.id();
        });
        while (!context.onGameThread(control::atMenu)) {
            if (context.remainingMillis() < 1500L) {
                throw new McpArgs.Invalid("The replay is still closing: get_state tells when the title screen is back.");
            }
            McpControlTools.pause(POLL_MILLIS);
        }
        JsonObject out = new JsonObject();
        out.addProperty("closed", true);
        out.addProperty("replayId", closed);
        return McpToolResult.of(out);
    }

    // ------------------------------------------------------------------ playback

    static McpToolResult playback(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        McpObserver.Replay r = McpControlTools.editable(observer);
        if (!args.has("playing") && !args.has("speed") && !args.has("until")) {
            throw new McpArgs.Invalid("Give playing, speed or until");
        }
        Double speed = args.number("speed", 0.1, 8.0);
        float step = 0.0F;
        if (speed != null) {
            float[] steps = control.speeds();
            List<String> shown = new ArrayList<>();
            for (float s : steps) {
                if (Math.abs(s - speed) < 1.0E-4) {
                    step = s;
                }
                shown.add(number(s));
            }
            if (step == 0.0F) {
                throw new McpArgs.Invalid("Argument 'speed' must be one of " + String.join(", ", shown));
            }
        }
        Integer until = args.optionalInteger("until", 0, McpObserveTools.MAX_TICK);
        if (until != null) {
            McpControlTools.checkTick(until, r.durationTicks(), "until");
            if (until <= r.tick()) {
                throw new McpArgs.Invalid("'until' " + until + " is not after the play head (" + r.tick() + "): seek goes back");
            }
            if (args.has("playing") && !args.bool("playing", true)) {
                throw new McpArgs.Invalid("'until' plays the replay: leave out playing=false");
            }
        }
        if (speed != null) {
            control.setSpeed(step);
        }
        if (until != null) {
            control.playTo(until);
        } else if (args.has("playing")) {
            control.setPaused(!args.bool("playing", true));
        }
        McpObserver.Replay now = observer.replay();
        JsonObject out = McpControlTools.header(now);
        out.addProperty("tick", now.tick());
        out.addProperty("time", McpJson.time(now.tick()));
        out.addProperty("playing", !now.paused());
        out.addProperty("speed", now.speed());
        if (until != null) {
            out.addProperty("pausesAt", until);
        }
        return McpToolResult.of(out);
    }

    static McpToolResult seek(McpObserver observer, McpArgs args) throws McpArgs.Invalid {
        McpObserver.Replay r = McpControlTools.editable(observer);
        int given = (args.has("tick") ? 1 : 0) + (args.has("time") ? 1 : 0) + (args.has("marker") ? 1 : 0) + (args.has("by") ? 1 : 0);
        if (given != 1) {
            throw new McpArgs.Invalid("Give exactly one of tick, time, marker and by");
        }
        int target;
        Marker marker = null;
        if (args.has("tick")) {
            target = McpControlTools.tick(args, "tick", r, r.tick());
        } else if (args.has("time")) {
            target = McpControlTools.parseTime(args.required("time"));
            if (target < 0) {
                throw new McpArgs.Invalid("Argument 'time' must be a time such as 1:02.5, 62.5 (seconds) or 1:00:02");
            }
            McpControlTools.checkTick(target, r.durationTicks(), "time");
        } else if (args.has("marker")) {
            marker = McpControlTools.marker(r.project(), args.required("marker"));
            target = Math.min(marker.tick, r.durationTicks());
        } else {
            long to = (long) r.tick() + args.integer("by", 0, -McpObserveTools.MAX_TICK, McpObserveTools.MAX_TICK);
            target = (int) Math.max(0, Math.min(r.durationTicks(), to));
        }
        int reached = observer.seek(target);
        JsonObject out = McpControlTools.header(observer.replay());
        out.addProperty("tick", reached);
        out.addProperty("time", McpJson.time(reached));
        if (marker != null) {
            JsonObject m = new JsonObject();
            m.addProperty("id", marker.id);
            m.addProperty("name", marker.name);
            out.add("marker", m);
        }
        return McpToolResult.of(out);
    }

    // ------------------------------------------------------------------ view

    static McpToolResult camera(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        McpObserver.Replay r = McpControlTools.editable(observer);
        boolean placing = false;
        for (String name : List.of("x", "y", "z", "yaw", "pitch", "roll", "fov", "lookAt", "lookAtEntity")) {
            placing |= args.has(name);
        }
        String view = args.choice("view", placing ? "free" : null, VIEWS);
        if (view == null) {
            throw new McpArgs.Invalid("Give view (free, path or entity), or the eye position and angles of the free camera");
        }
        if (!"free".equals(view) && placing) {
            throw new McpArgs.Invalid("Positions, angles and lookAt only apply to view=free");
        }
        if (!"entity".equals(view) && args.has("entity")) {
            throw new McpArgs.Invalid("Argument 'entity' only applies to view=entity (lookAtEntity aims a free camera)");
        }
        JsonObject out = McpControlTools.header(r);
        out.addProperty("view", view);
        switch (view) {
            case "path" -> {
                control.setCameraPath(true);
                out.addProperty("hasCameraPath", observer.projectCamera(r.tick()) != null);
            }
            case "entity" -> {
                String key = args.required("entity");
                String name = control.spectate(key);
                if (name == null) {
                    throw new McpArgs.Invalid("No entity '" + key + "' in the world at the play head (list_entities gives the ids)");
                }
                out.addProperty("entity", name);
            }
            default -> {
                McpCamera c = freeCamera(observer, args);
                control.placeCamera(c);
                out.add("camera", c.withSource("free").toJson());
            }
        }
        return McpToolResult.of(out);
    }

    /** The free camera the arguments ask for: the current view with the fields given, aimed when asked. */
    static McpCamera freeCamera(McpObserver observer, McpArgs args) throws McpArgs.Invalid {
        McpCamera current = observer.currentCamera();
        double max = McpObserveTools.MAX_COORDINATE;
        double x = args.number("x", current.x(), -max, max);
        double y = args.number("y", current.y(), -max, max);
        double z = args.number("z", current.z(), -max, max);
        double yaw = args.number("yaw", current.yaw(), -3600.0, 3600.0);
        double pitch = args.number("pitch", current.pitch(), -90.0, 90.0);
        double[] target = args.point("lookAt");
        String entity = args.string("lookAtEntity", null);
        if (target != null && entity != null) {
            throw new McpArgs.Invalid("Give lookAt or lookAtEntity, not both");
        }
        if ((target != null || entity != null) && (args.has("yaw") || args.has("pitch"))) {
            throw new McpArgs.Invalid("lookAt and lookAtEntity set yaw and pitch: leave those out");
        }
        if (entity != null) {
            McpEntity e = observer.entity(entity);
            if (e == null) {
                throw new McpArgs.Invalid("No entity '" + entity + "' in the world at the play head (list_entities gives the ids)");
            }
            target = new double[]{e.x, e.y + e.eyeHeight, e.z};
        }
        if (target != null) {
            double[] angles = aim(x, y, z, target[0], target[1], target[2]);
            yaw = angles[0];
            pitch = angles[1];
        }
        return new McpCamera(x, y, z, yaw, pitch, args.number("roll", current.roll(), -180.0, 180.0),
                args.number("fov", current.fov(), McpCamera.MIN_FOV, McpCamera.MAX_FOV), "free");
    }

    /** Yaw and pitch, in the game's degrees, of an eye at {@code (x, y, z)} looking at {@code (tx, ty, tz)}. */
    static double[] aim(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0E-9 && Math.abs(dy) < 1.0E-9) {
            return new double[]{0.0, 0.0};
        }
        double yaw = flat < 1.0E-9 ? 0.0 : Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, flat));
        return new double[]{McpCamera.round(yaw, 3), McpCamera.round(pitch, 3)};
    }

    private static String number(float f) {
        return f == Math.rint(f) ? Integer.toString((int) f) : String.format(Locale.ROOT, "%s", f);
    }
}
