package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.SpeedTrack;
import com.vyrriox.justreplay.editor.fx.TransitionType;
import com.vyrriox.justreplay.format.ReplayStorage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The edit tools: everything {@link ReplayProject} holds can be added, changed and removed, through the model's own
 * rules (the ones the editor uses) and saved the way the editor saves, so every change is one step of the editor's
 * undo history and shows at once in the editor. This class holds the shared plumbing, the cameras, keyframes, cuts,
 * speed changes and markers, and the project tools (save, undo, redo, files); the actors and the recorded entities
 * are in {@link McpActorTools}, audio, effects, overlays and the world in {@link McpMediaTools}.
 *
 * @author vyrriox
 */
public final class McpEditTools {
    public static final List<String> TOOLS = List.of("edit_camera", "edit_camera_keyframe", "edit_cut", "edit_speed",
            "edit_actor", "edit_actor_keyframe", "edit_actor_action", "edit_entity",
            "edit_audio_track", "edit_audio_clip", "set_audio_mix", "edit_effect", "set_effects", "edit_overlay", "set_world",
            "edit_marker", "save_project", "undo", "redo", "list_files");
    public static final List<String> FILE_KINDS = List.of("skins", "models", "audio", "images", "luts");
    public static final List<String> CAMERA_TYPES = List.of(ReplayProject.Camera.PATH, ReplayProject.Camera.STATIC,
            ReplayProject.Camera.FOLLOW);
    public static final List<String> CURVES = List.of("CATMULL_ROM", "BEZIER", "LINEAR");
    public static final List<String> TIMINGS = List.of("CONSTANT_SPEED", "KEYFRAMES");
    public static final List<String> FOLLOW_MODES = List.of("BEHIND", "SIDE", "FRONT", "ORBIT");
    public static final List<String> DIRECTIONS = List.of("LEFT", "RIGHT", "UP", "DOWN");
    /** Field of view of the cameras and keyframes of the project, as the editor's buttons bound it. */
    public static final double MIN_FOV = 10.0;
    public static final double MAX_FOV = 150.0;
    public static final int MAX_STEPS = 50;
    static final double EYE = McpCamera.EYE_HEIGHT;
    static final double MAX_C = McpObserveTools.MAX_COORDINATE;
    static final int MAX_T = McpObserveTools.MAX_TICK;
    private static final List<String> ADD_UPDATE_REMOVE = List.of("add", "update", "remove");

    private McpEditTools() {
    }

    static void register(McpRegistry registry, McpObserver observer, McpControl control) {
        registerCameras(registry, observer, control);
        McpActorTools.register(registry, observer, control);
        McpMediaTools.register(registry, observer, control);
        registerMarkers(registry, observer, control);
        registerProject(registry, observer, control);
    }

    // ------------------------------------------------------------------ plumbing

    /** What a change did besides the project's fields: actors to rebuild or to forget. */
    static final class Change {
        final List<ReplayProject.ActorData> rebuilt = new ArrayList<>();
        final List<ReplayProject.ActorData> removed = new ArrayList<>();
    }

    /** One change of the project; returns what the answer says about it. */
    @FunctionalInterface
    interface Edit {
        JsonObject apply(McpObserver.Replay replay, Change change) throws McpArgs.Invalid;
    }

    /**
     * Game thread: one change of the open project, then saved and shown as the editor does. A change that throws
     * must have written nothing: every edit checks its arguments (on a copy when needed) before it touches the project.
     */
    static McpToolResult edit(McpObserver observer, McpControl control, Edit edit) throws McpArgs.Invalid {
        McpObserver.Replay r = McpControlTools.editable(observer);
        Change change = new Change();
        JsonObject done = edit.apply(r, change);
        control.projectChanged(change.rebuilt, change.removed);
        JsonObject out = McpControlTools.header(r);
        for (Map.Entry<String, JsonElement> e : done.entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        out.addProperty("saved", true);
        out.addProperty("canUndo", control.canUndo());
        return McpToolResult.of(out);
    }

    /** The {@code action} argument, which every edit tool needs. */
    static String action(McpArgs args, List<String> actions) throws McpArgs.Invalid {
        if (!args.has("action")) {
            throw new McpArgs.Invalid("Argument 'action' is required: one of " + String.join(", ", actions));
        }
        return args.choice("action", null, actions);
    }

    /** Refuses every argument given that {@code action} does not take, so nothing asked is silently ignored. */
    static void only(McpArgs args, String action, Collection<String> allowed) throws McpArgs.Invalid {
        for (String name : args.names()) {
            if (!name.equals("action") && !allowed.contains(name)) {
                throw new McpArgs.Invalid("Argument '" + name + "' does not apply to action=" + action
                        + (allowed.isEmpty() ? " (it takes no other argument)" : ". It takes: " + String.join(", ", allowed)));
            }
        }
    }

    /** Argument names from strings and collections of them, in order. */
    static Set<String> names(Object... parts) {
        Set<String> out = new LinkedHashSet<>();
        for (Object part : parts) {
            if (part instanceof Collection<?> c) {
                for (Object o : c) {
                    out.add(o.toString());
                }
            } else {
                out.add(part.toString());
            }
        }
        return out;
    }

    /** A deep copy through the project's own JSON form, ids kept: changes are tried on it before the real thing. */
    @SuppressWarnings("unchecked")
    static <T> T copy(T value) {
        return (T) ReplayStorage.GSON.fromJson(ReplayStorage.GSON.toJson(value), value.getClass());
    }

    static McpArgs.Invalid nothing() {
        return new McpArgs.Invalid("Nothing to change: give the fields to change with the id of what to change");
    }

    /** An entity as the project names it: {@code actor:<id>} for an actor, else its UUID. */
    record Ref(String id, String name) {
    }

    /** Game thread: the entity a key names, an actor of the project ({@code actor:<id>}) or an entity at the play head. */
    static Ref entity(McpObserver observer, ReplayProject project, String key, String argument) throws McpArgs.Invalid {
        String k = key.trim();
        if (k.startsWith(Marker.ACTOR_PREFIX)) {
            ReplayProject.ActorData a = McpActorTools.actorById(project, k.substring(Marker.ACTOR_PREFIX.length()));
            if (a == null) {
                throw new McpArgs.Invalid("No actor has the id '" + k.substring(Marker.ACTOR_PREFIX.length())
                        + "' for '" + argument + "' (get_project with sections=actors gives them)");
            }
            return new Ref(Marker.ACTOR_PREFIX + a.id, McpActorTools.shownName(a));
        }
        McpEntity e = observer.entity(k);
        if (e == null) {
            throw new McpArgs.Invalid("No entity '" + k + "' in the world at the play head for '" + argument
                    + "' (list_entities gives the ids; an actor is also actor:<id>)");
        }
        if (McpEntity.ACTOR.equals(e.source) && !e.actorId.isEmpty()) {
            return new Ref(Marker.ACTOR_PREFIX + e.actorId, e.name);
        }
        return new Ref(e.uuid, e.name);
    }

    /** A camera by id, or by its exact name (any case) when only one camera has it. */
    static ReplayProject.Camera camera(ReplayProject p, String key) throws McpArgs.Invalid {
        ReplayProject.Camera byId = p.camera(key);
        if (byId != null) {
            return byId;
        }
        ReplayProject.Camera found = null;
        for (ReplayProject.Camera c : p.cameras) {
            if (c.name != null && c.name.equalsIgnoreCase(key)) {
                if (found != null) {
                    throw new McpArgs.Invalid("Several cameras are named '" + key + "': use its id");
                }
                found = c;
            }
        }
        if (found == null) {
            throw new McpArgs.Invalid("No camera has the id or name '" + key + "' (get_project with sections=cameras gives them)");
        }
        return found;
    }

    private static ReplayProject.Camera cameraOrSelected(ReplayProject p, McpArgs args) throws McpArgs.Invalid {
        return args.has("camera") ? camera(p, args.required("camera")) : p.selected();
    }

    /** Ticks of a list, for messages: the first 20. */
    static String ticks(List<Integer> ticks) {
        if (ticks.isEmpty()) {
            return "none";
        }
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < ticks.size() && i < 20; i++) {
            shown.add(Integer.toString(ticks.get(i)));
        }
        return String.join(", ", shown) + (ticks.size() > 20 ? "..." : "");
    }

    static float lens(double fov) {
        return (float) Math.max(MIN_FOV, Math.min(MAX_FOV, fov));
    }

    static JsonArray point(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(McpCamera.round(x, 4));
        a.add(McpCamera.round(y, 4));
        a.add(McpCamera.round(z, 4));
        return a;
    }

    // ------------------------------------------------------------------ cameras

    private static final McpFields<ReplayProject.Camera> CAMERA = new McpFields<ReplayProject.Camera>()
            .text("name", "Name of the camera.", 64, (c, v) -> c.name = v.trim())
            .choice("curve", "PATH: CATMULL_ROM goes through every keyframe, BEZIER is rounder, LINEAR draws straight lines.",
                    CURVES, (c, v) -> c.curve = v)
            .choice("timing", "PATH: CONSTANT_SPEED moves evenly from the first keyframe to the last, KEYFRAMES reaches each "
                    + "keyframe at its tick.", TIMINGS, (c, v) -> c.timing = v)
            .bool("ease", "PATH with CONSTANT_SPEED: starts and stops gently.", (c, v) -> c.ease = v)
            .number("roll", "STATIC and FOLLOW: roll in degrees (a PATH camera takes it from its keyframes).", -180.0, 180.0,
                    (c, v) -> c.roll = v.floatValue())
            .number("fov", "STATIC and FOLLOW: vertical field of view in degrees, 10 to 150.", MIN_FOV, MAX_FOV,
                    (c, v) -> c.fov = v.floatValue())
            .choice("followMode", "FOLLOW: BEHIND, SIDE, FRONT, or ORBIT around the target.", FOLLOW_MODES, (c, v) -> c.followMode = v)
            .number("distance", "FOLLOW: horizontal distance from the target in blocks.", 0.5, 64.0, (c, v) -> c.distance = v)
            .number("height", "FOLLOW: height above the target's eyes in blocks.", -16.0, 32.0, (c, v) -> c.height = v)
            .number("orbitSpeed", "FOLLOW with ORBIT: degrees per second, negative turns the other way.", -360.0, 360.0,
                    (c, v) -> c.orbitSpeed = v.floatValue())
            .number("smoothing", "FOLLOW: smoothing time in seconds, 0 for a rigid follow.", 0.0, 5.0, (c, v) -> c.smoothing = v.floatValue())
            .number("shake", "Handheld shake, 0 (off) to 1.", 0.0, 1.0, (c, v) -> c.shake = v.floatValue())
            .number("shakeSpeed", "Shake frequency, 1 is a calm handheld feel.", 0.1, 8.0, (c, v) -> c.shakeSpeed = v.floatValue());
    private static final Set<String> PATH_ONLY = Set.of("curve", "timing", "ease");
    private static final Set<String> FOLLOW_ONLY = Set.of("followMode", "distance", "height", "orbitSpeed", "smoothing", "target");
    private static final Set<String> PLACEMENT = Set.of("x", "y", "z", "yaw", "pitch");

    private static void registerCameras(McpRegistry registry, McpObserver observer, McpControl control) {
        registry.add(McpTool.builder("edit_camera")
                .title("Edit a camera")
                .description("Adds, changes, removes or selects a camera of the edit. PATH cameras follow their keyframes "
                        + "(edit_camera_keyframe), STATIC cameras hold one placement (x, y, z of the eye with yaw and pitch, or "
                        + "the current view), FOLLOW cameras track an entity from an offset. The selected camera is the one "
                        + "edited and exported when the edit has no cuts. Removing a camera removes its cuts and the effects "
                        + "bound to it; the edit keeps at least one camera.")
                .input(CAMERA.addTo(McpSchema.object()
                        .choice("action", "add, update, remove or select.", null, "add", "update", "remove", "select")
                        .string("camera", "update, remove, select: id or exact name of the camera.")
                        .choice("type", "add: PATH (default), STATIC or FOLLOW. A camera keeps its type.", null,
                                CAMERA_TYPES.toArray(new String[0])))
                        .number("x", "STATIC: eye X of the placement.", -MAX_C, MAX_C)
                        .number("y", "STATIC: eye Y of the placement.", -MAX_C, MAX_C)
                        .number("z", "STATIC: eye Z of the placement.", -MAX_C, MAX_C)
                        .number("yaw", "STATIC: yaw in degrees, 0 looks south (+Z).", -3600.0, 3600.0)
                        .number("pitch", "STATIC: pitch in degrees, positive looks down.", -90.0, 90.0)
                        .bool("fromView", "STATIC: placed at the current view (the default of a new STATIC camera). PATH, add "
                                + "only: a first keyframe at the play head from the current view.")
                        .string("target", "FOLLOW: the entity to follow (list_entities gives the ids; an actor is also actor:<id>).")
                        .string("lookAt", "STATIC and PATH: an entity the camera keeps aimed at, or an empty string for none.")
                        .bool("select", "add: makes it the selected camera.", true)
                        .required("action"))
                .destructive()
                .handler((args, context) -> camera(observer, control, args))
                .build());
        registry.add(McpTool.builder("edit_camera_keyframe")
                .title("Edit a camera keyframe")
                .description("Adds, changes or removes a keyframe of a PATH camera (the selected camera when 'camera' is left "
                        + "out). A keyframe holds the eye position, yaw, pitch, roll and field of view at a tick; a new one "
                        + "takes the current view for what is left out (set_camera places the view first, render_frame "
                        + "previews it), and replaces a keyframe already at its tick, as the editor's keyframe button does. "
                        + "x, y and z are the eye, as in render_frame; project.json stores the feet, 1.62 lower.")
                .input(McpSchema.object()
                        .choice("action", "add, update or remove.", null, "add", "update", "remove")
                        .string("camera", "Id or exact name of a PATH camera; the selected camera when left out.")
                        .integer("tick", "Tick of the keyframe; add: the play head when left out.", 0, MAX_T)
                        .integer("newTick", "update: moves the keyframe to this tick.", 0, MAX_T)
                        .number("x", "Eye X of the keyframe.", -MAX_C, MAX_C)
                        .number("y", "Eye Y of the keyframe.", -MAX_C, MAX_C)
                        .number("z", "Eye Z of the keyframe.", -MAX_C, MAX_C)
                        .number("yaw", "Yaw in degrees: 0 looks south (+Z), 90 west (-X), 180 north, -90 east.", -3600.0, 3600.0)
                        .number("pitch", "Pitch in degrees, positive looks down.", -90.0, 90.0)
                        .number("roll", "Roll in degrees (0 for a new keyframe).", -180.0, 180.0)
                        .number("fov", "Vertical field of view in degrees, 10 to 150 (the current view's for a new keyframe).",
                                MIN_FOV, MAX_FOV)
                        .point("lookAt", "Aims the keyframe at this point [x, y, z] (replaces yaw and pitch).")
                        .required("action"))
                .destructive()
                .handler((args, context) -> cameraKeyframe(observer, control, args))
                .build());
        registry.add(McpTool.builder("edit_cut")
                .title("Edit a camera cut")
                .description("Edits the cut track, which says which camera is live from which tick. add makes a camera live from "
                        + "a tick (the play head when left out), with a blend of the camera move and a picture transition; "
                        + "update and remove act on the cut at 'tick'; end sets where the cut track ends (left out: with the "
                        + "replay). A video export covers the cut track when there is one.")
                .input(McpSchema.object()
                        .choice("action", "add, update, remove or end.", null, "add", "update", "remove", "end")
                        .integer("tick", "Tick of the cut; add: the play head when left out. end: last tick of the track.", 0, MAX_T)
                        .integer("newTick", "update: moves the cut to this tick.", 0, MAX_T)
                        .string("camera", "add, update: id or exact name of the camera made live.")
                        .integer("blend", "Ticks over which the camera move blends into the new camera, 0 for a hard cut.", 0, 200)
                        .choice("transition", "Picture transition from the cut: " + String.join(", ", transitions()) + ".", null,
                                transitions().toArray(new String[0]))
                        .integer("transitionTicks", "Length of the transition in ticks.", 1, 200)
                        .choice("transitionDir", "Direction of a WIPE or SLIDE.", null, DIRECTIONS.toArray(new String[0]))
                        .required("action"))
                .destructive()
                .handler((args, context) -> cut(observer, control, args))
                .build());
        registry.add(McpTool.builder("edit_speed")
                .title("Edit the speed track")
                .description("Edits the speed changes of previews and exported videos: speed keys (from a tick the replay runs at "
                        + "a speed, ramping smoothly to the next key with ramp=true), timelapses (a stretch played in some "
                        + "seconds of video), freezes (the picture stops for some seconds) and the speeds at which game sounds "
                        + "are heard. clear removes them all.")
                .input(McpSchema.object()
                        .choice("action", "set_key, remove_key, add_timelapse, remove_timelapse, add_freeze, remove_freeze, "
                                        + "set_sounds or clear.", null, "set_key", "remove_key", "add_timelapse", "remove_timelapse",
                                "add_freeze", "remove_freeze", "set_sounds", "clear")
                        .integer("tick", "set_key, add_freeze: tick (the play head when left out). remove_key, remove_freeze: tick "
                                + "of the key or freeze.", 0, MAX_T)
                        .number("speed", "set_key: speed from the tick, 0.1 to 8.", SpeedTrack.MIN_SPEED, SpeedTrack.MAX_SPEED)
                        .bool("ramp", "set_key: changes smoothly to the next key.")
                        .integer("from", "add_timelapse, remove_timelapse: first tick of the timelapse.", 0, MAX_T)
                        .integer("to", "add_timelapse: last tick of the timelapse.", 0, MAX_T)
                        .number("seconds", "add_timelapse: seconds of video it lasts (5 when left out). add_freeze: seconds the "
                                + "picture holds (2 when left out).", 0.05, SpeedTrack.MAX_SECONDS)
                        .number("soundMin", "set_sounds: slowest speed at which game sounds play.", SpeedTrack.MIN_SPEED, SpeedTrack.MAX_SPEED)
                        .number("soundMax", "set_sounds: fastest speed at which game sounds play.", SpeedTrack.MIN_SPEED, SpeedTrack.MAX_SPEED)
                        .required("action"))
                .destructive()
                .handler((args, context) -> speed(observer, control, args))
                .build());
    }

    static List<String> transitions() {
        List<String> out = new ArrayList<>();
        for (TransitionType t : TransitionType.values()) {
            out.add(t.name());
        }
        return out;
    }

    static McpToolResult camera(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = action(args, List.of("add", "update", "remove", "select"));
        return edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    only(args, action, names(CAMERA.names(), "type", PLACEMENT, "fromView", "target", "lookAt", "select"));
                    String type = args.choice("type", ReplayProject.Camera.PATH, CAMERA_TYPES);
                    McpFields.Changes<ReplayProject.Camera> fields = CAMERA.read(args);
                    ReplayProject.Camera c = new ReplayProject.Camera(p.nextCameraName(), type);
                    c.fov = lens(observer.currentCamera().fov());
                    checkCameraFields(args, c);
                    fields.applyTo(c);
                    if (c.name.isEmpty()) {
                        c.name = p.nextCameraName();
                    }
                    placeCamera(observer, p, args, c, true, r.tick());
                    p.cameras.add(c);
                    if (args.bool("select", true)) {
                        p.select(c);
                    }
                    out.add("camera", cameraJson(c, p));
                }
                case "update" -> {
                    only(args, action, names("camera", CAMERA.names(), PLACEMENT, "fromView", "target", "lookAt"));
                    ReplayProject.Camera c = camera(p, args.required("camera"));
                    checkCameraFields(args, c);
                    McpFields.Changes<ReplayProject.Camera> fields = CAMERA.read(args);
                    if (fields.isEmpty() && !args.has("target") && !args.has("lookAt") && !args.has("fromView")
                            && PLACEMENT.stream().noneMatch(args::has)) {
                        throw nothing();
                    }
                    ReplayProject.Camera trial = copy(c);
                    fields.applyTo(trial);
                    placeCamera(observer, p, args, trial, false, r.tick());
                    fields.applyTo(c);
                    placeCamera(observer, p, args, c, false, r.tick());
                    if (c.name.isEmpty()) {
                        c.name = p.nextCameraName();
                    }
                    out.add("camera", cameraJson(c, p));
                }
                case "remove" -> {
                    only(args, action, Set.of("camera"));
                    ReplayProject.Camera c = camera(p, args.required("camera"));
                    if (p.cameras.size() <= 1) {
                        throw new McpArgs.Invalid("The edit keeps at least one camera: '" + c.name + "' is the last one");
                    }
                    long cuts = p.cuts.stream().filter(k -> k.camera.equals(c.id)).count();
                    long effects = p.effects.stream().filter(e -> c.id.equals(e.camera)).count();
                    p.removeCamera(c);
                    out.addProperty("removed", c.id);
                    out.addProperty("name", c.name);
                    out.addProperty("cutsRemoved", cuts);
                    out.addProperty("effectsRemoved", effects);
                    out.addProperty("selectedCamera", p.selected().id);
                }
                default -> {
                    only(args, action, Set.of("camera"));
                    ReplayProject.Camera c = camera(p, args.required("camera"));
                    p.select(c);
                    out.add("camera", cameraJson(c, p));
                }
            }
            return out;
        });
    }

    /** Refuses the fields that do nothing on a camera of this type. */
    private static void checkCameraFields(McpArgs args, ReplayProject.Camera c) throws McpArgs.Invalid {
        String type = c.type;
        if (args.has("type")) {
            type = args.choice("type", type, CAMERA_TYPES);
        }
        for (String name : args.names()) {
            if (PATH_ONLY.contains(name) && !ReplayProject.Camera.PATH.equals(type)) {
                throw new McpArgs.Invalid("'" + name + "' is for PATH cameras ('" + c.name + "' is " + type + ")");
            }
            if (FOLLOW_ONLY.contains(name) && !ReplayProject.Camera.FOLLOW.equals(type)) {
                throw new McpArgs.Invalid("'" + name + "' is for FOLLOW cameras ('" + c.name + "' is " + type + ")");
            }
            if ((name.equals("roll") || name.equals("fov")) && ReplayProject.Camera.PATH.equals(type)) {
                throw new McpArgs.Invalid("A PATH camera takes '" + name + "' from its keyframes: edit_camera_keyframe sets it");
            }
            if (PLACEMENT.contains(name) && !ReplayProject.Camera.STATIC.equals(type)) {
                throw new McpArgs.Invalid("'" + name + "' places a STATIC camera: a PATH camera gets keyframes from "
                        + "edit_camera_keyframe, a FOLLOW camera follows its target");
            }
            if (name.equals("lookAt") && ReplayProject.Camera.FOLLOW.equals(type)) {
                throw new McpArgs.Invalid("A FOLLOW camera looks at its target: 'lookAt' is for STATIC and PATH cameras");
            }
            if (name.equals("fromView") && ReplayProject.Camera.FOLLOW.equals(type)) {
                throw new McpArgs.Invalid("'fromView' is for STATIC and PATH cameras");
            }
        }
    }

    /** Writes the placement, the first keyframe from the view, the target and the look-at entity a call asks for. */
    private static void placeCamera(McpObserver observer, ReplayProject p, McpArgs args, ReplayProject.Camera c, boolean adding, int tick)
            throws McpArgs.Invalid {
        boolean xyz = args.has("x") || args.has("y") || args.has("z");
        if (xyz && !(args.has("x") && args.has("y") && args.has("z"))) {
            throw new McpArgs.Invalid("Give x, y and z together (the eye position)");
        }
        boolean placed = xyz || args.has("yaw") || args.has("pitch");
        if (args.has("fromView") && placed && args.bool("fromView", false)) {
            throw new McpArgs.Invalid("Give fromView or a placement (x, y, z, yaw, pitch), not both");
        }
        McpCamera view = observer.currentCamera();
        if (c.isStatic()) {
            boolean fromView = args.bool("fromView", adding && !placed);
            if (fromView) {
                c.x = view.x();
                c.y = view.y() - EYE;
                c.z = view.z();
                c.yaw = (float) view.yaw();
                c.pitch = (float) view.pitch();
            } else if (placed) {
                if (xyz) {
                    c.x = args.number("x", 0.0, -MAX_C, MAX_C);
                    c.y = args.number("y", 0.0, -MAX_C, MAX_C) - EYE;
                    c.z = args.number("z", 0.0, -MAX_C, MAX_C);
                } else if (adding) {
                    c.x = view.x();
                    c.y = view.y() - EYE;
                    c.z = view.z();
                }
                c.yaw = (float) args.number("yaw", adding ? view.yaw() : c.yaw, -3600.0, 3600.0);
                c.pitch = (float) args.number("pitch", adding ? view.pitch() : c.pitch, -90.0, 90.0);
            }
        } else if (c.isPath() && args.bool("fromView", false)) {
            if (!adding) {
                throw new McpArgs.Invalid("'fromView' adds the first keyframe of a new PATH camera: edit_camera_keyframe adds "
                        + "keyframes to an existing one");
            }
            c.setKeyframe(new ReplayProject.CameraKeyframe(tick, view.x(), view.y() - EYE, view.z(), (float) view.yaw(),
                    (float) view.pitch(), 0.0F, lens(view.fov())));
        }
        if (args.has("target")) {
            Ref ref = entity(observer, p, args.required("target"), "target");
            c.target = ref.id();
            c.targetName = ref.name();
        } else if (adding && c.isFollow()) {
            throw new McpArgs.Invalid("A FOLLOW camera needs 'target', the entity to follow (list_entities gives the ids)");
        }
        if (args.has("lookAt")) {
            String key = args.string("lookAt", "");
            if (key.isBlank()) {
                c.lookAt = "";
                c.lookAtName = "";
            } else {
                Ref ref = entity(observer, p, key, "lookAt");
                c.lookAt = ref.id();
                c.lookAtName = ref.name();
            }
        }
    }

    /** A camera for the answers: its fields as project.json has them, keyframes shortened, the eye of a STATIC camera. */
    static JsonObject cameraJson(ReplayProject.Camera c, ReplayProject p) {
        JsonObject o = McpJson.summarize(McpJson.tree(c)).getAsJsonObject();
        o.addProperty("selected", c.id.equals(p.selectedCamera));
        if (c.isStatic()) {
            o.add("eye", point(c.x, c.y + EYE, c.z));
        }
        return o;
    }

    static McpToolResult cameraKeyframe(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = action(args, ADD_UPDATE_REMOVE);
        return edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            ReplayProject.Camera c = cameraOrSelected(p, args);
            if (!c.isPath()) {
                throw new McpArgs.Invalid("Camera '" + c.name + "' is a " + c.type + " camera: only PATH cameras have keyframes "
                        + "(edit_camera action=add type=PATH makes one)");
            }
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    only(args, action, names("camera", "tick", "x", "y", "z", "yaw", "pitch", "roll", "fov", "lookAt"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    ReplayProject.CameraKeyframe k = keyframe(observer, args, new ReplayProject.CameraKeyframe(), true);
                    k.tick = tick;
                    boolean replaced = c.setKeyframe(k);
                    out.add("keyframe", keyJson(k));
                    out.addProperty("replaced", replaced);
                }
                case "update" -> {
                    only(args, action, names("camera", "tick", "newTick", "x", "y", "z", "yaw", "pitch", "roll", "fov", "lookAt"));
                    ReplayProject.CameraKeyframe k = keyframeAt(c, McpControlTools.requiredTick(args, "tick", r));
                    if (args.names().size() <= 2 + (args.has("camera") ? 1 : 0)) {
                        throw nothing();
                    }
                    ReplayProject.CameraKeyframe moved = keyframe(observer, args, copy(k), false);
                    if (args.has("newTick")) {
                        int to = McpControlTools.tick(args, "newTick", r, k.tick);
                        if (to != k.tick && c.keyframeAt(to) != null) {
                            throw new McpArgs.Invalid("Camera '" + c.name + "' already has a keyframe at tick " + to);
                        }
                        moved.tick = to;
                    }
                    c.keyframes.remove(k);
                    c.setKeyframe(moved);
                    out.add("keyframe", keyJson(moved));
                }
                default -> {
                    only(args, action, names("camera", "tick"));
                    ReplayProject.CameraKeyframe k = keyframeAt(c, McpControlTools.requiredTick(args, "tick", r));
                    c.keyframes.remove(k);
                    out.addProperty("removedTick", k.tick);
                }
            }
            JsonObject camera = new JsonObject();
            camera.addProperty("id", c.id);
            camera.addProperty("name", c.name);
            camera.addProperty("keyframes", c.keyframes.size());
            out.add("camera", camera);
            return out;
        });
    }

    private static ReplayProject.CameraKeyframe keyframeAt(ReplayProject.Camera c, int tick) throws McpArgs.Invalid {
        ReplayProject.CameraKeyframe k = c.keyframeAt(tick);
        if (k == null) {
            List<Integer> ticks = new ArrayList<>();
            c.keyframes.forEach(key -> ticks.add(key.tick));
            throw new McpArgs.Invalid("Camera '" + c.name + "' has no keyframe at tick " + tick + " (its keyframes: " + ticks(ticks) + ")");
        }
        return k;
    }

    /** Writes the placement arguments into {@code k}: from the current view for a new keyframe, from {@code k} otherwise. */
    private static ReplayProject.CameraKeyframe keyframe(McpObserver observer, McpArgs args, ReplayProject.CameraKeyframe k, boolean fresh)
            throws McpArgs.Invalid {
        boolean xyz = args.has("x") || args.has("y") || args.has("z");
        if (xyz && !(args.has("x") && args.has("y") && args.has("z"))) {
            throw new McpArgs.Invalid("Give x, y and z together (the eye position)");
        }
        McpCamera view = observer.currentCamera();
        double ex = fresh ? view.x() : k.x;
        double ey = fresh ? view.y() : k.y + EYE;
        double ez = fresh ? view.z() : k.z;
        if (xyz) {
            ex = args.number("x", 0.0, -MAX_C, MAX_C);
            ey = args.number("y", 0.0, -MAX_C, MAX_C);
            ez = args.number("z", 0.0, -MAX_C, MAX_C);
        }
        double yaw = fresh ? view.yaw() : k.yaw;
        double pitch = fresh ? view.pitch() : k.pitch;
        double[] target = args.point("lookAt");
        if (target != null) {
            if (args.has("yaw") || args.has("pitch")) {
                throw new McpArgs.Invalid("lookAt sets yaw and pitch: leave those out");
            }
            double[] angles = McpPlayTools.aim(ex, ey, ez, target[0], target[1], target[2]);
            yaw = angles[0];
            pitch = angles[1];
        }
        k.x = ex;
        k.y = ey - EYE;
        k.z = ez;
        k.yaw = (float) args.number("yaw", yaw, -3600.0, 3600.0);
        k.pitch = (float) args.number("pitch", pitch, -90.0, 90.0);
        k.roll = (float) args.number("roll", fresh ? 0.0 : k.roll, -180.0, 180.0);
        k.fov = (float) args.number("fov", fresh ? lens(view.fov()) : k.fov, MIN_FOV, MAX_FOV);
        return k;
    }

    static JsonObject keyJson(ReplayProject.CameraKeyframe k) {
        JsonObject o = McpJson.tree(k).getAsJsonObject();
        o.addProperty("time", McpJson.time(k.tick));
        o.add("eye", point(k.x, k.y + EYE, k.z));
        return o;
    }

    // ------------------------------------------------------------------ cuts

    private static final Set<String> CUT_FIELDS = Set.of("blend", "transition", "transitionTicks", "transitionDir");

    static McpToolResult cut(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = action(args, List.of("add", "update", "remove", "end"));
        return edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    only(args, action, names("tick", "camera", CUT_FIELDS));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    ReplayProject.Camera c = camera(p, args.required("camera"));
                    ReplayProject.Cut trial = new ReplayProject.Cut(tick, c.id, 0);
                    cutFields(args, trial);
                    ReplayProject.Cut cut = p.cutTo(tick, c, trial.blend);
                    if (cut == null) {
                        throw new McpArgs.Invalid("Camera '" + c.name + "' is already live at tick " + tick + ": nothing to cut");
                    }
                    cutFields(args, cut);
                    out.add("cut", cutJson(cut, p));
                }
                case "update" -> {
                    only(args, action, names("tick", "newTick", "camera", CUT_FIELDS));
                    ReplayProject.Cut cut = cutAt(p, McpControlTools.requiredTick(args, "tick", r));
                    if (args.names().size() <= 2) {
                        throw nothing();
                    }
                    ReplayProject.Camera c = args.has("camera") ? camera(p, args.required("camera")) : null;
                    int to = McpControlTools.tick(args, "newTick", r, cut.tick);
                    if (to != cut.tick) {
                        for (ReplayProject.Cut other : p.cuts) {
                            if (other != cut && other.tick == to) {
                                throw new McpArgs.Invalid("A cut is already at tick " + to);
                            }
                        }
                    }
                    cutFields(args, copy(cut));
                    cutFields(args, cut);
                    cut.tick = to;
                    if (c != null) {
                        cut.camera = c.id;
                    }
                    p.mergeCuts();
                    out.add("cut", cutJson(cut, p));
                    out.addProperty("merged", !p.cuts.contains(cut));
                }
                case "remove" -> {
                    only(args, action, Set.of("tick"));
                    ReplayProject.Cut cut = cutAt(p, McpControlTools.requiredTick(args, "tick", r));
                    p.cuts.remove(cut);
                    p.mergeCuts();
                    out.addProperty("removedTick", cut.tick);
                }
                default -> {
                    only(args, action, Set.of("tick"));
                    if (args.has("tick")) {
                        int end = McpControlTools.tick(args, "tick", r, -1);
                        if (!p.cuts.isEmpty() && end <= p.cuts.get(0).tick) {
                            throw new McpArgs.Invalid("The cut track must end after its first cut (tick " + p.cuts.get(0).tick + ")");
                        }
                        p.cutsEnd = end;
                    } else {
                        p.cutsEnd = -1;
                    }
                }
            }
            out.addProperty("cuts", p.cuts.size());
            out.addProperty("trackEnd", p.cutTrackEnd(r.durationTicks()));
            return out;
        });
    }

    private static void cutFields(McpArgs args, ReplayProject.Cut cut) throws McpArgs.Invalid {
        cut.blend = args.integer("blend", cut.blend, 0, 200);
        cut.transition = args.choice("transition", cut.transition, transitions());
        cut.transitionTicks = args.integer("transitionTicks", cut.transitionTicks, 1, 200);
        cut.transitionDir = args.choice("transitionDir", cut.transitionDir, DIRECTIONS);
    }

    private static ReplayProject.Cut cutAt(ReplayProject p, int tick) throws McpArgs.Invalid {
        List<Integer> ticks = new ArrayList<>();
        for (ReplayProject.Cut cut : p.cuts) {
            if (cut.tick == tick) {
                return cut;
            }
            ticks.add(cut.tick);
        }
        throw new McpArgs.Invalid("No cut at tick " + tick + " (cuts at: " + ticks(ticks) + ")");
    }

    private static JsonObject cutJson(ReplayProject.Cut cut, ReplayProject p) {
        JsonObject o = McpJson.tree(cut).getAsJsonObject();
        ReplayProject.Camera c = p.camera(cut.camera);
        o.addProperty("cameraName", c == null ? "" : c.name);
        o.addProperty("time", McpJson.time(cut.tick));
        return o;
    }

    // ------------------------------------------------------------------ speed

    static McpToolResult speed(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = action(args, List.of("set_key", "remove_key", "add_timelapse", "remove_timelapse", "add_freeze",
                "remove_freeze", "set_sounds", "clear"));
        return edit(observer, control, (r, change) -> {
            SpeedTrack track = r.project().speed;
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "set_key" -> {
                    only(args, action, Set.of("tick", "speed", "ramp"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    if (!args.has("speed")) {
                        throw new McpArgs.Invalid("Argument 'speed' is required (0.1 to 8)");
                    }
                    float speed = (float) args.number("speed", 1.0, SpeedTrack.MIN_SPEED, SpeedTrack.MAX_SPEED);
                    track.setKey(tick, speed);
                    if (args.has("ramp")) {
                        track.keyAt(tick).ramp = args.bool("ramp", false);
                    }
                }
                case "remove_key" -> {
                    only(args, action, Set.of("tick"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    SpeedTrack.Key key = track.keyAt(tick);
                    if (key == null) {
                        List<Integer> ticks = new ArrayList<>();
                        track.keys.forEach(k -> ticks.add(k.tick));
                        throw new McpArgs.Invalid("No speed key at tick " + tick + " (keys at: " + ticks(ticks) + ")");
                    }
                    track.keys.remove(key);
                }
                case "add_timelapse" -> {
                    only(args, action, Set.of("from", "to", "seconds"));
                    int from = McpControlTools.requiredTick(args, "from", r);
                    int to = McpControlTools.requiredTick(args, "to", r);
                    if (to <= from) {
                        throw new McpArgs.Invalid("'to' must be after 'from'");
                    }
                    for (SpeedTrack.Timelapse t : track.timelapses) {
                        if (from < t.to && t.from < to) {
                            throw new McpArgs.Invalid("It overlaps the timelapse from " + t.from + " to " + t.to + ": remove it first");
                        }
                    }
                    track.timelapses.add(new SpeedTrack.Timelapse(from, to,
                            (float) args.number("seconds", 5.0, 0.05, SpeedTrack.MAX_SECONDS)));
                }
                case "remove_timelapse" -> {
                    only(args, action, Set.of("from"));
                    int from = McpControlTools.requiredTick(args, "from", r);
                    if (!track.timelapses.removeIf(t -> t.from == from)) {
                        throw new McpArgs.Invalid("No timelapse starts at tick " + from);
                    }
                }
                case "add_freeze" -> {
                    only(args, action, Set.of("tick", "seconds"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    float seconds = (float) args.number("seconds", 2.0, 0.05, SpeedTrack.MAX_SECONDS);
                    boolean replaced = track.freezes.removeIf(f -> f.tick == tick);
                    track.freezes.add(new SpeedTrack.Freeze(tick, seconds));
                    out.addProperty("replaced", replaced);
                }
                case "remove_freeze" -> {
                    only(args, action, Set.of("tick"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    if (!track.freezes.removeIf(f -> f.tick == tick)) {
                        throw new McpArgs.Invalid("No freeze at tick " + tick);
                    }
                }
                case "set_sounds" -> {
                    only(args, action, Set.of("soundMin", "soundMax"));
                    float min = (float) args.number("soundMin", track.soundMin, SpeedTrack.MIN_SPEED, SpeedTrack.MAX_SPEED);
                    float max = (float) args.number("soundMax", track.soundMax, SpeedTrack.MIN_SPEED, SpeedTrack.MAX_SPEED);
                    if (!args.has("soundMin") && !args.has("soundMax")) {
                        throw nothing();
                    }
                    if (min > max) {
                        throw new McpArgs.Invalid("'soundMin' must not be above 'soundMax'");
                    }
                    track.soundMin = min;
                    track.soundMax = max;
                }
                default -> {
                    only(args, action, Set.of());
                    r.project().speed = new SpeedTrack();
                    track = r.project().speed;
                }
            }
            track.normalize();
            out.add("speed", McpJson.tree(track));
            return out;
        });
    }

    // ------------------------------------------------------------------ markers

    private static void registerMarkers(McpRegistry registry, McpObserver observer, McpControl control) {
        registry.add(McpTool.builder("edit_marker")
                .title("Edit a marker")
                .description("Adds, changes or removes a marker of the open replay, as the Markers tab does: a moment "
                        + "(INSTANT), a stretch of time (RANGE, with 'end'), a spot of the world (POINT, x y z) or an entity "
                        + "(ENTITY), with a name, a colour and a note for whoever edits next. The type follows the fields when "
                        + "left out. Markers the player placed are edited the same way: change them only when asked.")
                .input(McpSchema.object()
                        .choice("action", "add, update or remove.", null, "add", "update", "remove")
                        .string("marker", "update, remove: id of the marker (list_markers gives them), or its exact name.")
                        .choice("type", "INSTANT, RANGE, POINT or ENTITY.", null, Marker.TYPES.toArray(new String[0]))
                        .string("name", "Name shown on the timeline (128 characters at most).")
                        .string("note", "Note for whoever edits the replay next (2000 characters at most).")
                        .integer("tick", "First tick; add: the play head when left out.", 0, MAX_T)
                        .integer("end", "Last tick of a stretch; -1 makes it a single moment again.", -1, MAX_T)
                        .number("x", "POINT: X of the spot.", -MAX_C, MAX_C)
                        .number("y", "POINT: Y of the spot.", -MAX_C, MAX_C)
                        .number("z", "POINT: Z of the spot.", -MAX_C, MAX_C)
                        .string("dimension", "POINT: dimension of the spot, the one shown when left out.")
                        .string("entity", "ENTITY: the entity (list_entities gives the ids), actor:<id> for an actor, or "
                                + "recorder for the recording player.")
                        .string("color", "Colour: " + String.join(", ", Arrays.asList(Marker.COLOR_NAMES)) + ", or #RRGGBB.")
                        .required("action"))
                .destructive()
                .handler((args, context) -> marker(observer, control, args))
                .build());
    }

    private static final Set<String> MARKER_FIELDS = Set.of("type", "name", "note", "tick", "end", "x", "y", "z", "dimension",
            "entity", "color");

    static McpToolResult marker(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = action(args, ADD_UPDATE_REMOVE);
        return edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    only(args, action, MARKER_FIELDS);
                    Marker m = Marker.instant(McpControlTools.tick(args, "tick", r, r.tick()), p.nextMarkerName());
                    String type = args.choice("type", args.has("entity") ? Marker.ENTITY : args.has("x") || args.has("y")
                            || args.has("z") ? Marker.POINT : args.has("end") ? Marker.RANGE : Marker.INSTANT, Marker.TYPES);
                    if (Marker.RANGE.equals(type) && !args.has("end")) {
                        throw new McpArgs.Invalid("A RANGE marker needs 'end', its last tick");
                    }
                    writeMarker(observer, r, args, m, type);
                    p.addMarker(m);
                    out.add("marker", McpJson.marker(m));
                }
                case "update" -> {
                    only(args, action, names("marker", MARKER_FIELDS));
                    Marker m = McpControlTools.marker(p, args.required("marker"));
                    if (args.names().size() <= 2) {
                        throw nothing();
                    }
                    String type = args.choice("type", null, Marker.TYPES);
                    writeMarker(observer, r, args, copy(m), type);
                    writeMarker(observer, r, args, m, type);
                    p.markers = Marker.normalizeAll(p.markers);
                    out.add("marker", McpJson.marker(m));
                }
                default -> {
                    only(args, action, Set.of("marker"));
                    Marker m = McpControlTools.marker(p, args.required("marker"));
                    p.removeMarker(m.id);
                    out.addProperty("removed", m.id);
                    out.addProperty("name", m.name);
                }
            }
            out.addProperty("markers", p.markers.size());
            return out;
        });
    }

    /** Writes the marker fields given into {@code m}; {@code type} is the type asked for, or null to keep or infer it. */
    private static void writeMarker(McpObserver observer, McpObserver.Replay r, McpArgs args, Marker m, String type) throws McpArgs.Invalid {
        String name = args.text("name", Marker.MAX_NAME);
        if (name != null) {
            if (name.isBlank()) {
                throw new McpArgs.Invalid("A marker needs a name: leave 'name' out for the next default one");
            }
            m.name = name.trim();
        }
        String note = args.text("note", Marker.MAX_NOTE);
        if (note != null) {
            m.note = note;
        }
        if (args.has("color")) {
            m.color = markerColor(args);
        }
        int duration = r.durationTicks();
        boolean hasTick = args.has("tick");
        int tick = McpControlTools.tick(args, "tick", r, m.tick);
        if (args.has("end")) {
            int end = args.integer("end", -1, -1, MAX_T);
            if (end < 0) {
                m.clearEnd();
                if (hasTick) {
                    m.moveTo(tick, duration);
                }
            } else {
                Marker.RangeCheck check = m.setRange(tick, end, duration);
                if (check != Marker.RangeCheck.OK) {
                    throw new McpArgs.Invalid(switch (check) {
                        case EMPTY -> "'end' (" + end + ") must be after the first tick (" + tick + ")";
                        case PAST_END -> "'end' (" + end + ") is past the end of the replay (" + duration + " ticks)";
                        default -> "The stretch " + tick + " to " + end + " is not in the replay";
                    });
                }
            }
        } else if (hasTick) {
            m.moveTo(tick, duration);
        }
        boolean xyz = args.has("x") || args.has("y") || args.has("z");
        String target = type != null ? type : args.has("entity") ? Marker.ENTITY : xyz ? Marker.POINT : null;
        if (target == null) {
            return;
        }
        switch (target) {
            case Marker.POINT -> {
                if (args.has("entity")) {
                    throw new McpArgs.Invalid("'entity' pins an ENTITY marker, not a POINT");
                }
                if (!xyz && !m.isPoint()) {
                    throw new McpArgs.Invalid("A POINT marker needs x, y and z, the spot of the world");
                }
                if (xyz && !(args.has("x") && args.has("y") && args.has("z"))) {
                    throw new McpArgs.Invalid("Give x, y and z together");
                }
                String dimension = args.text("dimension", 128);
                m.placeAt(args.number("x", m.x, -MAX_C, MAX_C), args.number("y", m.y, -MAX_C, MAX_C),
                        args.number("z", m.z, -MAX_C, MAX_C), dimension != null ? dimension
                                : m.isPoint() && !m.dimension.isEmpty() ? m.dimension : observer.world().dimension);
            }
            case Marker.ENTITY -> {
                if (xyz || args.has("dimension")) {
                    throw new McpArgs.Invalid("x, y, z and dimension place a POINT marker, not an ENTITY one");
                }
                if (!args.has("entity")) {
                    if (!m.isEntity()) {
                        throw new McpArgs.Invalid("An ENTITY marker needs 'entity'");
                    }
                    return;
                }
                String key = args.required("entity");
                if (Marker.RECORDER.equalsIgnoreCase(key)) {
                    String recorder = r.meta() == null || r.meta().recorderName == null ? "" : r.meta().recorderName;
                    m.follow(Marker.RECORDER, recorder);
                } else {
                    McpEntity e = key.startsWith(Marker.ACTOR_PREFIX) ? null : observer.entity(key);
                    if (e != null && e.recorder) {
                        m.follow(Marker.RECORDER, e.name);
                    } else {
                        Ref ref = entity(observer, r.project(), key, "entity");
                        m.follow(ref.id(), ref.name());
                    }
                }
            }
            default -> {
                if (xyz || args.has("entity") || args.has("dimension")) {
                    throw new McpArgs.Invalid("x, y, z, dimension and entity pin POINT and ENTITY markers, not " + target);
                }
                if (Marker.RANGE.equals(target) && !m.isRange()) {
                    throw new McpArgs.Invalid("A RANGE marker needs 'end', its last tick");
                }
                if (Marker.INSTANT.equals(target) && args.has("end") && m.isRange()) {
                    throw new McpArgs.Invalid("An INSTANT marker has no end: leave 'end' out or give -1");
                }
                m.unpin();
                if (Marker.INSTANT.equals(target)) {
                    m.clearEnd();
                }
            }
        }
    }

    private static int markerColor(McpArgs args) throws McpArgs.Invalid {
        String value = args.required("color");
        for (int i = 0; i < Marker.COLOR_NAMES.length; i++) {
            if (Marker.COLOR_NAMES[i].equalsIgnoreCase(value)) {
                return Marker.COLORS[i];
            }
        }
        try {
            return McpFields.color(args, "color");
        } catch (McpArgs.Invalid e) {
            throw new McpArgs.Invalid("Argument 'color' must be one of " + String.join(", ", Arrays.asList(Marker.COLOR_NAMES))
                    + ", or #RRGGBB");
        }
    }

    // ------------------------------------------------------------------ project

    private static void registerProject(McpRegistry registry, McpObserver observer, McpControl control) {
        registry.add(McpTool.builder("save_project")
                .title("Save the edit")
                .description("Writes the edit of the open replay to its project.json. Every change made in the editor or over "
                        + "MCP is already saved when it is made, as the editor does; this writes it once more and says what "
                        + "the edit holds.")
                .additive()
                .idempotent()
                .handler((args, context) -> save(observer, control))
                .build());
        registry.add(McpTool.builder("undo")
                .title("Undo")
                .description("Takes back the last changes of the edit, as Ctrl+Z in the editor does. The history is the editor's, "
                        + "shared with the player: it holds the last 100 changes made since the replay opened, by hand or over "
                        + "MCP, and is lost when the replay closes.")
                .input(McpSchema.object().integer("steps", "How many changes to take back.", 1, MAX_STEPS, 1))
                .destructive()
                .handler((args, context) -> history(observer, control, args, false))
                .build());
        registry.add(McpTool.builder("redo")
                .title("Redo")
                .description("Applies again the changes undo took back, as Ctrl+Y in the editor does; a new change empties it.")
                .input(McpSchema.object().integer("steps", "How many changes to apply again.", 1, MAX_STEPS, 1))
                .destructive()
                .handler((args, context) -> history(observer, control, args, true))
                .build());
        registry.add(McpTool.builder("list_files")
                .title("Files for the edit")
                .description("The files the edit can use, from the justreplay folders of the game: skins (PNG skins of player "
                        + "actors), models (Blockbench models of model actors, with their animations), audio (music and sounds "
                        + "for audio clips), images (picture overlays) and luts (LUT filters). Names are given as the edit "
                        + "stores them; the player adds files by dropping them in those folders.")
                .input(McpSchema.object()
                        .choice("kind", "Only this kind of file; all of them when left out.", null, FILE_KINDS.toArray(new String[0])))
                .readOnly()
                .handler((args, context) -> files(control, args.choice("kind", null, FILE_KINDS)))
                .build());
    }

    static McpToolResult save(McpObserver observer, McpControl control) throws McpArgs.Invalid {
        McpObserver.Replay r = McpControlTools.replay(observer);
        control.saveProject();
        ReplayProject p = r.project();
        JsonObject out = McpControlTools.header(r);
        out.addProperty("saved", true);
        out.addProperty("file", r.id() + "/" + ReplayStorage.PROJECT);
        JsonObject holds = new JsonObject();
        holds.addProperty("cameras", p.cameras.size());
        holds.addProperty("cameraKeyframes", p.cameras.stream().mapToInt(c -> c.keyframes.size()).sum());
        holds.addProperty("cuts", p.cuts.size());
        holds.addProperty("actors", p.actors.size());
        holds.addProperty("markers", p.markers.size());
        holds.addProperty("audioTracks", p.audioTracks.size());
        holds.addProperty("effects", p.effects.size());
        holds.addProperty("overlays", p.overlays.size());
        holds.addProperty("entityEdits", p.entityEdits.size());
        out.add("holds", holds);
        out.addProperty("canUndo", control.canUndo());
        return McpToolResult.of(out);
    }

    static McpToolResult history(McpObserver observer, McpControl control, McpArgs args, boolean redo) throws McpArgs.Invalid {
        McpObserver.Replay r = McpControlTools.editable(observer);
        int steps = args.integer("steps", 1, 1, MAX_STEPS);
        int done = 0;
        while (done < steps && (redo ? control.redo() : control.undo())) {
            done++;
        }
        if (done == 0) {
            throw new McpArgs.Invalid(redo ? "Nothing to redo: undo has not taken back any change since the last one"
                    : "Nothing to undo: the edit's history is empty (it holds the changes made since the replay opened)");
        }
        JsonObject out = McpControlTools.header(r);
        out.addProperty(redo ? "redone" : "undone", done);
        out.addProperty("canUndo", control.canUndo());
        out.addProperty("canRedo", control.canRedo());
        return McpToolResult.of(out);
    }

    static McpToolResult files(McpControl control, String kind) {
        JsonObject out = new JsonObject();
        for (String k : FILE_KINDS) {
            if (kind != null && !kind.equals(k)) {
                continue;
            }
            JsonArray list = new JsonArray();
            for (String file : control.files(k)) {
                if ("models".equals(k)) {
                    JsonObject model = new JsonObject();
                    model.addProperty("file", file);
                    JsonArray animations = new JsonArray();
                    control.modelAnimations(file).forEach(animations::add);
                    model.add("animations", animations);
                    list.add(model);
                } else {
                    list.add(file);
                }
            }
            out.add(k, list);
        }
        out.addProperty("folders", "justreplay/<kind> in the game folder");
        return McpToolResult.of(out);
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
