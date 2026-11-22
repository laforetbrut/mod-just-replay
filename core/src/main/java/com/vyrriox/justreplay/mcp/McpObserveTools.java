package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.WorldSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The observation tools of the MCP server: the frame of a tick as a picture, the entities of a tick, the path of one
 * entity, the world, a block, the players, and where points fall on a frame. They work on the replay open in the
 * game. A tick other than the play head is reached by jumping there and back within one game tick, so the player
 * never sees it, though going back rebuilds the world from a checkpoint; nothing is ever changed or saved.
 *
 * @author vyrriox
 */
public final class McpObserveTools {
    /** The tools, in the order {@code tools/list} gives them. */
    public static final List<String> TOOLS = List.of("render_frame", "list_entities", "get_entity", "get_entity_track",
            "get_world", "get_block", "list_players", "project_to_screen");
    public static final List<String> CAMERAS = List.of("auto", "project", "current", "free");
    public static final List<String> FORMATS = List.of(McpImage.JPEG, McpImage.PNG);
    private static final List<String> DETAILS = List.of("summary", "full");
    /** Longest stretch one track covers: 10 minutes. */
    public static final int MAX_TRACK_RANGE = 12_000;
    public static final int MAX_TRACK_SAMPLES = 500;
    public static final int DEFAULT_TRACK_SAMPLES = 100;
    public static final int MAX_TYPES = 32;
    public static final int MAX_TARGETS = 64;
    public static final int MAX_PLAYERS = 200;
    /** A frame may take this long: the export path plays a second of the replay and waits for the chunks first. */
    public static final int FRAME_TIMEOUT_SECONDS = 60;
    /** Tools that jump through the replay may take this long. */
    public static final int SEEK_TIMEOUT_SECONDS = 30;
    static final int MAX_TICK = 100_000_000;
    static final double MAX_COORDINATE = 30_000_000.0;
    private static final String TICK = "Tick to look at (20 per second). The play head when left out; another tick is reached by "
            + "jumping there and back, unseen by the player.";

    /** Added to the instructions the server gives when a client connects. */
    public static final String INSTRUCTIONS = " To see the replay: render_frame draws the frame of a tick as a picture (from the "
            + "project's camera, the current view or a camera you place), list_entities and get_entity tell what is in the world "
            + "at a tick, get_entity_track follows one entity over time, get_world, get_block and list_players describe the world, "
            + "and project_to_screen tells where points, markers or entities fall on a frame of the same camera and size. "
            + "Positions are in blocks, angles in degrees (yaw 0 looks south, +Z).";

    private McpObserveTools() {
    }

    public static void register(McpRegistry registry, McpObserver observer) {
        registry.add(McpTool.builder("render_frame")
                .title("Render a frame")
                .description("Draws the frame of a tick of the open replay and returns it as a picture (JPEG by default, PNG for "
                        + "exact pixels) with the camera it was drawn from. By default it is a clean video frame, rendered the way "
                        + "the video export renders it (effects, letterbox, overlays, no editor), from the project's camera path "
                        + "when it has one, else from the current view; ui=true gives the window as the player sees it instead, "
                        + "editor and HUD included. At most 1280 pixels a side and 1280x720 pixels in all; a bigger size is "
                        + "scaled down. Takes about a second, longer while chunks load.")
                .input(camera(McpSchema.object()
                        .integer("tick", TICK, 0, MAX_TICK))
                        .integer("width", "Width in pixels (64 to 1280). Left out, it follows the window's shape from the "
                                + "height, or is 640 when both are left out.", 1, 100_000)
                        .integer("height", "Height in pixels (64 to 1280). Left out, it follows the window's shape.", 1, 100_000)
                        .choice("format", "jpeg (small) or png (exact).", McpImage.JPEG, McpImage.JPEG, McpImage.PNG)
                        .integer("quality", "JPEG quality, 1 to 100.", 1, 100, McpImage.DEFAULT_QUALITY)
                        .bool("ui", "true: the window as the player sees it, editor and HUD included, drawn at window size "
                                + "then scaled. false: a clean video frame like the export.", false))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(FRAME_TIMEOUT_SECONDS)
                .handler((args, context) -> renderFrame(observer, args, context))
                .build());
        registry.add(McpTool.builder("list_entities")
                .title("Entities at a tick")
                .description("The entities of the replay world at a tick: id, ref, UUID, type, name, recorded entity or editor "
                        + "actor, position, rotation, velocity (blocks per second), health, equipment, vehicle and passengers. "
                        + "Filters: types, source, a name, a sphere around a point, a marker or an entity. Nearest first around "
                        + "a centre, by id otherwise; one page at a time (nextOffset gives the next one).")
                .input(McpSchema.object()
                        .integer("tick", TICK, 0, MAX_TICK)
                        .strings("types", "Types to keep: full ids (minecraft:pig), names without namespace (pig) or whole "
                                + "mods (create:*).", MAX_TYPES)
                        .choice("source", "recorded: entities of the recording only; actor: actors added in the editor only.",
                                null, McpEntity.RECORDED, McpEntity.ACTOR)
                        .string("query", "Keeps the entities whose name, custom name or type contains this text (any case).")
                        .number("x", "X of the centre of the sphere to look in (with y and z).", -MAX_COORDINATE, MAX_COORDINATE)
                        .number("y", "Y of the centre.", -MAX_COORDINATE, MAX_COORDINATE)
                        .number("z", "Z of the centre.", -MAX_COORDINATE, MAX_COORDINATE)
                        .string("marker", "Id of a marker to look around: a POINT marker's spot or an ENTITY marker's entity.")
                        .string("near", "Ref, UUID or id of an entity to look around.")
                        .number("radius", "Radius of the sphere in blocks (32 when a centre is given).", 0.0, McpEntityQuery.MAX_RADIUS)
                        .integer("limit", "Most entities given.", 1, McpEntityQuery.MAX_LIMIT, McpEntityQuery.DEFAULT_LIMIT)
                        .integer("offset", "Entities skipped first, to page through a long list.", 0, 1_000_000, 0)
                        .choice("detail", "summary or full (adds size, eye position, pose, custom name, item names; at most "
                                + McpEntityQuery.MAX_FULL_LIMIT + " entities a page).",
                                "summary", "summary", "full"))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> listEntities(observer, args, context))
                .build());
        registry.add(McpTool.builder("get_entity")
                .title("Entity")
                .description("Everything about one entity at a tick: position, eye, rotation, velocity, size, pose, health, "
                        + "equipment with item names, what it rides and what rides it, and the markers pinned to it.")
                .input(McpSchema.object()
                        .string("id", "Ref, UUID or id of the entity, as list_entities gives them (actor:<id> for an actor, "
                                + "recorder for the recording player).")
                        .integer("tick", TICK, 0, MAX_TICK)
                        .required("id"))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> getEntity(observer, args, context))
                .build());
        registry.add(McpTool.builder("get_entity_track")
                .title("Entity path")
                .description("Where one entity is over a stretch of the replay, sampled at regular ticks: compact rows "
                        + "[tick, x, y, z, yaw, pitch], with the distance travelled, the top speed and the box it moved in. "
                        + "At most 10 minutes (12000 ticks) and 500 samples per call.")
                .input(McpSchema.object()
                        .string("id", "Ref, UUID or id of the entity (prefer the ref or UUID: an actor's id changes when it is rebuilt).")
                        .integer("from", "First tick (the play head minus 5 seconds when left out).", 0, MAX_TICK)
                        .integer("to", "Last tick (5 seconds after from when left out).", 0, MAX_TICK)
                        .integer("step", "Ticks between samples; when left out the stretch is cut into 'samples' samples.", 1, MAX_TRACK_RANGE)
                        .integer("samples", "Number of samples when no step is given.", 2, MAX_TRACK_SAMPLES, DEFAULT_TRACK_SAMPLES)
                        .required("id"))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> entityTrack(observer, args, context))
                .build());
        registry.add(McpTool.builder("get_world")
                .title("World and time")
                .description("The replay's clock (length, play head, speed, paused) and the world at a tick: dimension, time "
                        + "of day as shown and as recorded, weather, difficulty, counts of entities, players and chunks, the "
                        + "project's time and weather settings, and the camera the window shows.")
                .input(McpSchema.object()
                        .integer("tick", TICK, 0, MAX_TICK))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> world(observer, args, context))
                .build());
        registry.add(McpTool.builder("get_block")
                .title("Block")
                .description("The block at a position at a tick: id, state properties, fluid, block entity, biome and light "
                        + "(as the game computed it, it may lag right after a jump), or that its chunk is not loaded.")
                .input(McpSchema.object()
                        .integer("x", "X of the block (east is +X).", -30_000_000, 30_000_000)
                        .integer("y", "Y of the block (height).", -4096, 4096)
                        .integer("z", "Z of the block (south is +Z).", -30_000_000, 30_000_000)
                        .integer("tick", TICK, 0, MAX_TICK)
                        .required("x", "y", "z"))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> block(observer, args, context))
                .build());
        registry.add(McpTool.builder("list_players")
                .title("Players")
                .description("The players of the replay at a tick: who recorded it, the players present in the world with their "
                        + "position, and the tab list (name, game mode, latency).")
                .input(McpSchema.object()
                        .integer("tick", TICK, 0, MAX_TICK)
                        .integer("limit", "Most players of each list given.", 1, MAX_PLAYERS, 100))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> players(observer, args, context))
                .build());
        registry.add(McpTool.builder("project_to_screen")
                .title("Project to the frame")
                .description("Where points of the world, markers and entities fall on a frame drawn by render_frame with the same "
                        + "tick, camera, size and ui arguments: pixel x from the left and y from the top, depth in front of the "
                        + "camera, whether it is in front and on the picture. An entity gives its centre, feet and head.")
                .input(camera(McpSchema.object()
                        .points("points", "Points of the world, each [x, y, z].", MAX_TARGETS)
                        .strings("markers", "Ids of markers with a place (POINT, ENTITY), or 'all' for every one.", MAX_TARGETS)
                        .strings("entities", "Refs, UUIDs or ids of entities.", MAX_TARGETS)
                        .integer("tick", TICK, 0, MAX_TICK))
                        .integer("width", "Width of the frame in pixels, as given to render_frame.", 1, 100_000)
                        .integer("height", "Height of the frame in pixels, as given to render_frame.", 1, 100_000)
                        .bool("ui", "The frame shows the window (render_frame ui=true).", false))
                .readOnly()
                .offGameThread()
                .timeoutSeconds(SEEK_TIMEOUT_SECONDS)
                .handler((args, context) -> projectToScreen(observer, args, context))
                .build());
    }

    /** The camera arguments render_frame and project_to_screen share. */
    private static McpSchema camera(McpSchema schema) {
        return schema
                .choice("camera", "auto: the project's camera path when it has one, else the current view. project: the camera "
                        + "path, as the video shows it. current: the view the window shows now. free: the camera given by x, y, "
                        + "z, yaw, pitch, roll and fov (chosen by itself when x, y and z are given).", null,
                        CAMERAS.toArray(new String[0]))
                .number("x", "Eye X of a free camera.", -MAX_COORDINATE, MAX_COORDINATE)
                .number("y", "Eye Y of a free camera.", -MAX_COORDINATE, MAX_COORDINATE)
                .number("z", "Eye Z of a free camera.", -MAX_COORDINATE, MAX_COORDINATE)
                .number("yaw", "Yaw of a free camera in degrees: 0 looks south (+Z), 90 west (-X), 180 north, -90 east.", -3600.0, 3600.0)
                .number("pitch", "Pitch of a free camera in degrees: positive looks down, 90 straight down.", -90.0, 90.0)
                .number("roll", "Roll of a free camera in degrees.", -180.0, 180.0)
                .number("fov", "Vertical field of view of a free camera in degrees (the game's setting when left out).",
                        McpCamera.MIN_FOV, McpCamera.MAX_FOV);
    }

    // ------------------------------------------------------------------ render_frame

    /** What the game thread prepared for a frame. */
    private record FramePlan(CompletableFuture<McpObserver.Frame> future, McpImage.Size size, String source, JsonObject replay) {
    }

    static McpToolResult renderFrame(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        String format = args.choice("format", McpImage.JPEG, FORMATS);
        int quality = args.integer("quality", McpImage.DEFAULT_QUALITY, 1, 100);
        boolean ui = args.bool("ui", false);
        FramePlan plan = context.onGameThread(() -> {
            McpObserver.Replay r = observer.replay();
            Integer asked = args.optionalInteger("tick", 0, MAX_TICK);
            if (r == null && (!ui || asked != null)) {
                throw noReplay();
            }
            int tick = r == null ? 0 : tick(args, r);
            Aim aim = aim(observer, r, tick, args, ui);
            int[] window = observer.windowSize();
            McpImage.Size size = McpImage.fit(args.optionalInteger("width", 1, 100_000), args.optionalInteger("height", 1, 100_000),
                    window[0] / (double) Math.max(1, window[1]));
            if (r != null && r.exporting()) {
                throw new McpArgs.Invalid("A video export or another frame is being drawn; try again once it is done.");
            }
            CompletableFuture<McpObserver.Frame> future = observer.render(new McpObserver.FrameRequest(tick, aim.override(),
                    size.width(), size.height(), ui));
            return new FramePlan(future, size, aim.source(), r == null ? null : replayInfo(r, tick));
        });
        McpObserver.Frame frame;
        try {
            frame = plan.future().get(context.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            plan.future().cancel(true);
            throw new McpArgs.Invalid("The frame was not drawn in time (the game may still be loading chunks); try again.");
        } catch (CancellationException e) {
            throw new McpArgs.Invalid("The frame was cancelled before it was drawn.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof McpArgs.Invalid invalid) {
                throw invalid;
            }
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
        McpImage.Size size = plan.size();
        byte[] rgb = McpImage.scale(frame.rgba(), frame.width(), frame.height(), size.width(), size.height());
        byte[] data = McpImage.encode(format, rgb, size.width(), size.height(), quality);
        JsonObject out = new JsonObject();
        if (plan.replay() != null) {
            out.add("replay", plan.replay());
        }
        out.addProperty("tick", frame.tick());
        out.addProperty("time", McpJson.time(frame.tick()));
        out.addProperty("ui", ui);
        out.add("camera", frame.camera().withSource(plan.source()).toJson());
        JsonObject picture = new JsonObject();
        picture.addProperty("width", size.width());
        picture.addProperty("height", size.height());
        picture.addProperty("format", format);
        picture.addProperty("mimeType", McpImage.mimeType(format));
        picture.addProperty("bytes", data.length);
        picture.addProperty("renderedWidth", frame.width());
        picture.addProperty("renderedHeight", frame.height());
        if (size.clamped()) {
            picture.addProperty("note", "The size asked for was brought within " + McpImage.MAX_SIDE + " pixels a side and "
                    + McpImage.MAX_PIXELS + " pixels in all.");
        }
        out.add("picture", picture);
        return McpToolResult.image(data, McpImage.mimeType(format), out);
    }

    /**
     * The camera a frame or a projection uses.
     *
     * @param camera   the camera itself (always set)
     * @param override what the frame must be drawn from, or null to leave the game's own camera (the camera path for
     *                 a video frame, the view shown for the window)
     */
    record Aim(McpCamera camera, McpCamera override, String source) {
    }

    /** Game thread: the camera the arguments ask for. */
    static Aim aim(McpObserver observer, McpObserver.Replay r, int tick, McpArgs args, boolean ui) throws McpArgs.Invalid {
        boolean placed = args.has("x") || args.has("y") || args.has("z");
        String mode = args.choice("camera", placed ? "free" : "auto", CAMERAS);
        boolean free = "free".equals(mode);
        if (!free) {
            for (String name : List.of("x", "y", "z", "yaw", "pitch", "roll", "fov")) {
                if (args.has(name)) {
                    throw new McpArgs.Invalid("Argument '" + name + "' only applies to camera=free");
                }
            }
        }
        McpCamera current = observer.currentCamera().withSource("current");
        switch (mode) {
            case "free" -> {
                Double x = args.number("x", -MAX_COORDINATE, MAX_COORDINATE);
                Double y = args.number("y", -MAX_COORDINATE, MAX_COORDINATE);
                Double z = args.number("z", -MAX_COORDINATE, MAX_COORDINATE);
                if (x == null || y == null || z == null) {
                    throw new McpArgs.Invalid("A free camera needs x, y and z (the eye position)");
                }
                McpCamera c = new McpCamera(x, y, z, args.number("yaw", 0.0, -3600.0, 3600.0), args.number("pitch", 0.0, -90.0, 90.0),
                        args.number("roll", 0.0, -180.0, 180.0), args.number("fov", current.fov(), McpCamera.MIN_FOV, McpCamera.MAX_FOV),
                        "free");
                return new Aim(c, c, "free");
            }
            case "current" -> {
                return new Aim(current, ui ? null : current, "current");
            }
            case "project" -> {
                McpCamera p = r == null ? null : observer.projectCamera(tick);
                if (p == null) {
                    throw new McpArgs.Invalid("The project has no camera keyframes; use camera=current or camera=free");
                }
                return new Aim(p.withSource("project"), ui ? p.withSource("project") : null, "project");
            }
            default -> {
                McpCamera p = r == null ? null : observer.projectCamera(tick);
                if (p != null) {
                    return new Aim(p.withSource("project"), ui ? p.withSource("project") : null, "project");
                }
                return new Aim(current, ui ? null : current, "current");
            }
        }
    }

    // ------------------------------------------------------------------ entities

    /** Entities of a tick and the answer's header, read on the game thread. */
    private record Seen(McpObserver.Replay replay, int tick, List<McpEntity> entities) {
    }

    static McpToolResult listEntities(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        List<String> types = args.strings("types", MAX_TYPES);
        String source = args.choice("source", null, McpEntity.SOURCES);
        String query = args.string("query", "");
        int limit = args.integer("limit", McpEntityQuery.DEFAULT_LIMIT, 1, McpEntityQuery.MAX_LIMIT);
        int offset = args.integer("offset", 0, 0, 1_000_000);
        boolean full = "full".equals(args.choice("detail", "summary", DETAILS));
        if (full) {
            limit = Math.min(limit, McpEntityQuery.MAX_FULL_LIMIT);
        }
        Seen seen = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int tick = tick(args, r);
            return new Seen(r, tick, at(observer, r, tick, true, observer::entities));
        });
        Center center = center(seen, args);
        double radius = args.number("radius", McpEntityQuery.DEFAULT_RADIUS, 0.0, McpEntityQuery.MAX_RADIUS);
        if (center == null && args.has("radius")) {
            throw new McpArgs.Invalid("Argument 'radius' needs a centre: x, y and z, a marker or an entity ('near')");
        }
        McpEntityQuery q = new McpEntityQuery(types, source, query, center == null ? null : center.point(), radius, offset, limit);
        McpEntityQuery.Page page = q.apply(seen.entities());
        JsonObject out = header(seen.replay(), seen.tick());
        out.addProperty("total", page.total());
        out.addProperty("offset", page.offset());
        out.addProperty("count", page.entities().size());
        if (page.nextOffset() >= 0) {
            out.addProperty("nextOffset", page.nextOffset());
        }
        if (center != null) {
            JsonObject c = McpEntity.xyz(center.point()[0], center.point()[1], center.point()[2], 3);
            c.addProperty("from", center.from());
            out.add("center", c);
            out.addProperty("radius", radius);
        }
        JsonArray list = new JsonArray();
        for (int i = 0; i < page.entities().size(); i++) {
            JsonObject e = page.entities().get(i).toJson(full);
            if (center != null) {
                e.addProperty("distance", McpCamera.round(page.distances().get(i), 2));
            }
            list.add(e);
        }
        out.add("entities", list);
        return McpToolResult.of(out);
    }

    /** Centre of a sphere and what it was taken from. */
    private record Center(double[] point, String from) {
    }

    private static Center center(Seen seen, McpArgs args) throws McpArgs.Invalid {
        boolean placed = args.has("x") || args.has("y") || args.has("z");
        String marker = args.string("marker", null);
        String near = args.string("near", null);
        int given = (placed ? 1 : 0) + (marker != null ? 1 : 0) + (near != null ? 1 : 0);
        if (given > 1) {
            throw new McpArgs.Invalid("Give one centre: x, y and z, or a marker, or an entity ('near')");
        }
        if (placed) {
            Double x = args.number("x", -MAX_COORDINATE, MAX_COORDINATE);
            Double y = args.number("y", -MAX_COORDINATE, MAX_COORDINATE);
            Double z = args.number("z", -MAX_COORDINATE, MAX_COORDINATE);
            if (x == null || y == null || z == null) {
                throw new McpArgs.Invalid("A centre needs x, y and z");
            }
            return new Center(new double[]{x, y, z}, "point");
        }
        if (marker != null) {
            Marker m = seen.replay().project().marker(marker.trim());
            if (m == null) {
                throw new McpArgs.Invalid("The open replay has no marker with the id '" + marker + "' (ids come from list_markers)");
            }
            double[] place = place(m, seen.entities());
            if (place == null) {
                throw new McpArgs.Invalid(m.isEntity() ? "The entity of marker '" + m.id + "' is not in the world at tick " + seen.tick()
                        : "Marker '" + m.id + "' is a " + m.type + " marker: it has no place in the world");
            }
            return new Center(place, "marker " + m.id);
        }
        if (near != null) {
            McpEntity e = find(seen.entities(), near);
            if (e == null) {
                throw notFound(near, seen.tick());
            }
            return new Center(new double[]{e.x, e.y, e.z}, "entity " + e.ref());
        }
        return null;
    }

    /** Where a marker is at the tick of {@code entities}: a POINT's spot, an ENTITY's entity, else null. */
    static double[] place(Marker m, List<McpEntity> entities) {
        if (m.isPoint()) {
            return new double[]{m.x, m.y, m.z};
        }
        if (m.isEntity()) {
            McpEntity e = find(entities, m.target);
            return e == null ? null : new double[]{e.x, e.y, e.z};
        }
        return null;
    }

    static McpEntity find(List<McpEntity> entities, String key) {
        for (McpEntity e : entities) {
            if (e.matches(key)) {
                return e;
            }
        }
        return null;
    }

    static McpToolResult getEntity(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        String key = args.required("id");
        Seen seen = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int tick = tick(args, r);
            return new Seen(r, tick, at(observer, r, tick, true, observer::entities));
        });
        McpEntity e = find(seen.entities(), key);
        if (e == null) {
            throw notFound(key, seen.tick());
        }
        JsonObject out = header(seen.replay(), seen.tick());
        JsonObject entity = e.toJson(true);
        if (e.vehicle != McpEntity.NONE) {
            McpEntity v = find(seen.entities(), Integer.toString(e.vehicle));
            if (v != null) {
                entity.add("vehicle", brief(v));
            }
        }
        if (!e.passengers.isEmpty()) {
            JsonArray riders = new JsonArray();
            for (int i = 0; i < e.passengers.size() && i < McpEntity.MAX_PASSENGERS; i++) {
                McpEntity p = find(seen.entities(), Integer.toString(e.passengers.get(i)));
                if (p != null) {
                    riders.add(brief(p));
                }
            }
            entity.add("passengers", riders);
        }
        JsonArray markers = new JsonArray();
        for (Marker m : seen.replay().project().markers) {
            if (m.isEntity() && e.matches(m.target)) {
                JsonObject mark = new JsonObject();
                mark.addProperty("id", m.id);
                mark.addProperty("name", m.name);
                markers.add(mark);
            }
        }
        entity.add("markers", markers);
        out.add("entity", entity);
        return McpToolResult.of(out);
    }

    private static JsonObject brief(McpEntity e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("ref", e.ref());
        o.addProperty("type", e.type);
        o.addProperty("name", McpEntity.cut(e.name));
        return o;
    }

    // ------------------------------------------------------------------ track

    /** The samples of a track, read on the game thread. */
    private record Track(McpObserver.Replay replay, int from, int to, int step, List<double[]> samples, McpEntity last, int absent) {
    }

    static McpToolResult entityTrack(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        String key = args.required("id");
        int samples = args.integer("samples", DEFAULT_TRACK_SAMPLES, 2, MAX_TRACK_SAMPLES);
        Integer stepArg = args.optionalInteger("step", 1, MAX_TRACK_RANGE);
        Track track = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int duration = r.durationTicks();
            Integer fromArg = args.optionalInteger("from", 0, MAX_TICK);
            Integer toArg = args.optionalInteger("to", 0, MAX_TICK);
            int from = fromArg != null ? fromArg : Math.max(0, (toArg != null ? toArg : r.tick()) - 100);
            int to = toArg != null ? toArg : Math.min(duration, from + 100);
            checkTick(from, duration, "from");
            checkTick(to, duration, "to");
            if (to < from) {
                throw new McpArgs.Invalid("'to' (" + to + ") is before 'from' (" + from + ")");
            }
            if (to - from > MAX_TRACK_RANGE) {
                throw new McpArgs.Invalid("A track covers at most " + MAX_TRACK_RANGE + " ticks (10 minutes); ask for several stretches");
            }
            int step = trackStep(from, to, stepArg, samples);
            if ((to - from) / step + 1 > MAX_TRACK_SAMPLES) {
                throw new McpArgs.Invalid("That step gives more than " + MAX_TRACK_SAMPLES + " samples; use a larger step");
            }
            if (r.exporting() && (from != r.tick() || to != from)) {
                throw exporting();
            }
            List<double[]> rows = new ArrayList<>();
            McpEntity[] last = new McpEntity[1];
            int[] absent = new int[1];
            try {
                for (int t = from; t <= to; t += step) {
                    observer.seek(t);
                    McpEntity e = observer.entity(key);
                    if (e == null) {
                        absent[0]++;
                    } else {
                        rows.add(new double[]{t, e.x, e.y, e.z, e.yaw, e.pitch});
                        last[0] = e;
                    }
                }
            } finally {
                observer.seek(r.tick());
            }
            return new Track(r, from, to, step, rows, last[0], absent[0]);
        });
        if (track.last() == null) {
            throw new McpArgs.Invalid("No entity '" + key + "' between ticks " + track.from() + " and " + track.to()
                    + " (list_entities gives the ids)");
        }
        JsonObject out = new JsonObject();
        out.add("replay", replayInfo(track.replay(), track.replay().tick()));
        McpEntity e = track.last();
        out.add("entity", brief(e));
        out.addProperty("from", track.from());
        out.addProperty("to", track.to());
        out.addProperty("step", track.step());
        JsonArray columns = new JsonArray();
        for (String c : List.of("tick", "x", "y", "z", "yaw", "pitch")) {
            columns.add(c);
        }
        out.add("columns", columns);
        JsonArray rows = new JsonArray();
        double distance = 0.0;
        double topSpeed = 0.0;
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] previous = null;
        for (double[] s : track.samples()) {
            JsonArray row = new JsonArray();
            row.add((int) s[0]);
            row.add(McpCamera.round(s[1], 2));
            row.add(McpCamera.round(s[2], 2));
            row.add(McpCamera.round(s[3], 2));
            row.add(McpCamera.round(s[4], 1));
            row.add(McpCamera.round(s[5], 1));
            rows.add(row);
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], s[i + 1]);
                max[i] = Math.max(max[i], s[i + 1]);
            }
            if (previous != null) {
                double d = Math.sqrt(sq(s[1] - previous[1]) + sq(s[2] - previous[2]) + sq(s[3] - previous[3]));
                distance += d;
                topSpeed = Math.max(topSpeed, d / Math.max(1.0, s[0] - previous[0]) * McpJson.TICKS_PER_SECOND);
            }
            previous = s;
        }
        out.add("samples", rows);
        JsonObject summary = new JsonObject();
        summary.addProperty("present", track.samples().size());
        summary.addProperty("absent", track.absent());
        summary.addProperty("firstTick", (int) track.samples().get(0)[0]);
        summary.addProperty("lastTick", (int) track.samples().get(track.samples().size() - 1)[0]);
        summary.addProperty("distance", McpCamera.round(distance, 2));
        summary.addProperty("topSpeed", McpCamera.round(topSpeed, 2));
        summary.add("min", McpEntity.xyz(min[0], min[1], min[2], 2));
        summary.add("max", McpEntity.xyz(max[0], max[1], max[2], 2));
        out.add("summary", summary);
        return McpToolResult.of(out);
    }

    /** Ticks between two samples: the one asked for, or the stretch cut into {@code samples} samples. */
    static int trackStep(int from, int to, Integer step, int samples) {
        if (step != null) {
            return Math.max(1, step);
        }
        int range = to - from;
        return range == 0 ? 1 : Math.max(1, (int) Math.ceil(range / (double) Math.max(1, samples - 1)));
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ world, block, players

    private record WorldSeen(McpObserver.Replay replay, int tick, McpObserver.World world, McpCamera camera) {
    }

    static McpToolResult world(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        WorldSeen seen = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int tick = tick(args, r);
            return at(observer, r, tick, false, () -> new WorldSeen(r, tick, observer.world(), observer.currentCamera()));
        });
        McpObserver.Replay r = seen.replay();
        JsonObject out = new JsonObject();
        JsonObject replay = replayInfo(r, seen.tick());
        replay.addProperty("paused", r.paused());
        replay.addProperty("speed", r.speed());
        out.add("replay", replay);
        out.addProperty("tick", seen.tick());
        out.addProperty("time", McpJson.time(seen.tick()));
        McpObserver.World w = seen.world();
        JsonObject world = new JsonObject();
        world.addProperty("dimension", w.dimension);
        world.addProperty("dayTime", Math.floorMod(w.dayTime, WorldSettings.DAY));
        world.addProperty("clock", clock(w.dayTime));
        world.addProperty("day", Math.floorDiv(w.dayTime, (long) WorldSettings.DAY));
        world.addProperty("recordedDayTime", Math.floorMod(w.recordedDayTime, WorldSettings.DAY));
        world.addProperty("recordedClock", clock(w.recordedDayTime));
        world.addProperty("gameTime", w.gameTime);
        world.addProperty("weather", w.thundering ? "thunder" : w.raining ? "rain" : "clear");
        world.addProperty("rainLevel", McpCamera.round(w.rainLevel, 2));
        world.addProperty("thunderLevel", McpCamera.round(w.thunderLevel, 2));
        world.addProperty("difficulty", w.difficulty);
        world.addProperty("entities", w.entities);
        world.addProperty("players", w.players);
        world.addProperty("loadedChunks", w.loadedChunks);
        out.add("world", world);
        WorldSettings settings = r.project().world;
        JsonObject project = new JsonObject();
        project.addProperty("timeMode", settings.timeMode);
        if (WorldSettings.TIME_FIXED.equals(settings.timeMode)) {
            project.addProperty("time", settings.time);
        }
        project.addProperty("weather", settings.weather);
        out.add("projectSettings", project);
        out.add("camera", seen.camera().withSource("current").toJson());
        return McpToolResult.of(out);
    }

    /** Time of day as a 24 hour clock: the day starts at 6:00 at tick 0. */
    static String clock(long dayTime) {
        long t = Math.floorMod(dayTime, WorldSettings.DAY);
        long hours = (t / 1000 + 6) % 24;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private record BlockSeen(McpObserver.Replay replay, int tick, McpObserver.Block block) {
    }

    static McpToolResult block(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        int x = args.integer("x", 0, -30_000_000, 30_000_000);
        int y = args.integer("y", 0, -4096, 4096);
        int z = args.integer("z", 0, -30_000_000, 30_000_000);
        for (String name : List.of("x", "y", "z")) {
            if (!args.has(name)) {
                throw new McpArgs.Invalid("Argument '" + name + "' is required");
            }
        }
        BlockSeen seen = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int tick = tick(args, r);
            return at(observer, r, tick, false, () -> new BlockSeen(r, tick, observer.block(x, y, z)));
        });
        JsonObject out = header(seen.replay(), seen.tick());
        JsonObject position = new JsonObject();
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", z);
        out.add("position", position);
        McpObserver.Block b = seen.block();
        JsonObject block = new JsonObject();
        block.addProperty("loaded", b.loaded);
        if (b.loaded) {
            block.addProperty("id", b.id);
            block.addProperty("air", b.air);
            JsonObject properties = new JsonObject();
            for (Map.Entry<String, String> p : b.properties.entrySet()) {
                properties.addProperty(p.getKey(), p.getValue());
            }
            block.add("properties", properties);
            if (!b.fluid.isEmpty()) {
                block.addProperty("fluid", b.fluid);
            }
            if (!b.blockEntity.isEmpty()) {
                block.addProperty("blockEntity", b.blockEntity);
            }
            block.addProperty("biome", b.biome);
            block.addProperty("blockLight", b.blockLight);
            block.addProperty("skyLight", b.skyLight);
        }
        out.add("block", block);
        return McpToolResult.of(out);
    }

    private record PlayersSeen(McpObserver.Replay replay, int tick, List<McpEntity> players, List<McpObserver.Player> tab) {
    }

    static McpToolResult players(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        int limit = args.integer("limit", 100, 1, MAX_PLAYERS);
        PlayersSeen seen = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int tick = tick(args, r);
            return at(observer, r, tick, false, () -> {
                List<McpEntity> players = new ArrayList<>();
                for (McpEntity e : observer.entities()) {
                    if (e.player && McpEntity.RECORDED.equals(e.source)) {
                        players.add(e);
                    }
                }
                return new PlayersSeen(r, tick, players, observer.tabList());
            });
        });
        McpObserver.Replay r = seen.replay();
        JsonObject out = header(r, seen.tick());
        JsonObject recorder = new JsonObject();
        recorder.addProperty("name", r.meta() == null ? "" : McpEntity.cut(r.meta().recorderName));
        recorder.addProperty("uuid", r.meta() == null ? "" : r.meta().recorderUuid);
        McpEntity self = find(seen.players(), Marker.RECORDER);
        recorder.addProperty("present", self != null);
        if (self != null) {
            recorder.addProperty("id", self.id);
            recorder.add("position", McpEntity.xyz(self.x, self.y, self.z, 2));
        }
        out.add("recorder", recorder);
        JsonArray present = new JsonArray();
        for (int i = 0; i < seen.players().size() && i < limit; i++) {
            McpEntity e = seen.players().get(i);
            JsonObject p = new JsonObject();
            p.addProperty("id", e.id);
            p.addProperty("ref", e.ref());
            p.addProperty("name", McpEntity.cut(e.name));
            p.addProperty("uuid", e.uuid);
            p.add("position", McpEntity.xyz(e.x, e.y, e.z, 2));
            for (McpObserver.Player t : seen.tab()) {
                if (t.uuid().equalsIgnoreCase(e.uuid)) {
                    p.addProperty("gameMode", t.gameMode());
                }
            }
            present.add(p);
        }
        out.addProperty("presentTotal", seen.players().size());
        out.add("present", present);
        JsonArray tab = new JsonArray();
        for (int i = 0; i < seen.tab().size() && i < limit; i++) {
            McpObserver.Player t = seen.tab().get(i);
            JsonObject p = new JsonObject();
            p.addProperty("name", McpEntity.cut(t.name()));
            p.addProperty("uuid", t.uuid());
            p.addProperty("gameMode", t.gameMode());
            p.addProperty("latency", t.latency());
            tab.add(p);
        }
        out.addProperty("tabListTotal", seen.tab().size());
        out.add("tabList", tab);
        return McpToolResult.of(out);
    }

    // ------------------------------------------------------------------ project_to_screen

    private record Projected(McpObserver.Replay replay, int tick, McpCamera camera, int[] window, List<McpEntity> entities) {
    }

    static McpToolResult projectToScreen(McpObserver observer, McpArgs args, McpContext context) throws Exception {
        List<double[]> points = args.points("points", MAX_TARGETS);
        List<String> markers = args.strings("markers", MAX_TARGETS);
        List<String> entityKeys = args.strings("entities", MAX_TARGETS);
        boolean ui = args.bool("ui", false);
        if (points.isEmpty() && markers.isEmpty() && entityKeys.isEmpty()) {
            throw new McpArgs.Invalid("Give points, markers or entities to project");
        }
        boolean needEntities = !entityKeys.isEmpty() || !markers.isEmpty();
        Projected seen = context.onGameThread(() -> {
            McpObserver.Replay r = requireReplay(observer);
            int tick = tick(args, r);
            Aim aim = aim(observer, r, tick, args, ui);
            List<McpEntity> entities = needEntities ? at(observer, r, tick, false, observer::entities) : List.of();
            return new Projected(r, tick, aim.camera(), observer.windowSize(), entities);
        });
        int[] window = seen.window();
        McpImage.Size size = McpImage.fit(args.optionalInteger("width", 1, 100_000), args.optionalInteger("height", 1, 100_000),
                window[0] / (double) Math.max(1, window[1]));
        Screen screen = new Screen(seen.camera(), size.width(), size.height(), ui, window[0], window[1]);
        JsonArray targets = new JsonArray();
        for (double[] p : points) {
            JsonObject t = new JsonObject();
            t.addProperty("kind", "point");
            t.add("world", McpEntity.xyz(p[0], p[1], p[2], 3));
            screen.put(t, p[0], p[1], p[2]);
            targets.add(t);
        }
        List<Marker> chosen = new ArrayList<>();
        if (markers.size() == 1 && "all".equalsIgnoreCase(markers.get(0))) {
            for (Marker m : seen.replay().project().markers) {
                if (m.isPoint() || m.isEntity()) {
                    chosen.add(m);
                }
            }
        } else {
            for (String id : markers) {
                Marker m = seen.replay().project().marker(id);
                if (m == null) {
                    throw new McpArgs.Invalid("The open replay has no marker with the id '" + id + "' (ids come from list_markers)");
                }
                chosen.add(m);
            }
        }
        for (int i = 0; i < chosen.size() && targets.size() < MAX_TARGETS; i++) {
            Marker m = chosen.get(i);
            JsonObject t = new JsonObject();
            t.addProperty("kind", "marker");
            t.addProperty("id", m.id);
            t.addProperty("name", m.name);
            t.addProperty("type", m.type);
            McpEntity e = m.isEntity() ? find(seen.entities(), m.target) : null;
            if (e != null) {
                entity(screen, t, e);
            } else {
                double[] place = place(m, seen.entities());
                if (place == null) {
                    t.addProperty("placed", false);
                    t.addProperty("reason", m.isEntity() ? "its entity is not in the world at this tick" : "it has no place in the world");
                } else {
                    t.add("world", McpEntity.xyz(place[0], place[1], place[2], 3));
                    screen.put(t, place[0], place[1], place[2]);
                }
            }
            targets.add(t);
        }
        for (String key : entityKeys) {
            JsonObject t = new JsonObject();
            t.addProperty("kind", "entity");
            McpEntity e = find(seen.entities(), key);
            if (e == null) {
                t.addProperty("key", key);
                t.addProperty("placed", false);
                t.addProperty("reason", "not in the world at this tick");
            } else {
                t.addProperty("ref", e.ref());
                t.addProperty("type", e.type);
                t.addProperty("name", McpEntity.cut(e.name));
                entity(screen, t, e);
            }
            targets.add(t);
        }
        JsonObject out = header(seen.replay(), seen.tick());
        out.add("camera", seen.camera().toJson());
        JsonObject picture = new JsonObject();
        picture.addProperty("width", size.width());
        picture.addProperty("height", size.height());
        picture.addProperty("ui", ui);
        out.add("picture", picture);
        out.add("targets", targets);
        return McpToolResult.of(out);
    }

    private static void entity(Screen screen, JsonObject t, McpEntity e) {
        double mid = e.y + Math.max(0.0, e.height) / 2.0;
        t.add("world", McpEntity.xyz(e.x, mid, e.z, 3));
        screen.put(t, e.x, mid, e.z);
        McpCamera.Projection feet = screen.project(e.x, e.y, e.z);
        McpCamera.Projection head = screen.project(e.x, e.y + Math.max(0.0, e.height), e.z);
        t.add("feet", pixel(feet));
        t.add("head", pixel(head));
    }

    private static JsonArray pixel(McpCamera.Projection p) {
        JsonArray a = new JsonArray();
        a.add(McpCamera.round(p.screenX(), 1));
        a.add(McpCamera.round(p.screenY(), 1));
        return a;
    }

    /**
     * The picture points land on: drawn at its own size, or for {@code ui} at window size then cropped around the
     * centre to the picture's shape and scaled, as {@link McpImage#scale} does.
     */
    static final class Screen {
        private final McpCamera camera;
        private final int width;
        private final int height;
        private final boolean ui;
        private final int windowWidth;
        private final int windowHeight;

        Screen(McpCamera camera, int width, int height, boolean ui, int windowWidth, int windowHeight) {
            this.camera = camera;
            this.width = width;
            this.height = height;
            this.ui = ui;
            this.windowWidth = Math.max(1, windowWidth);
            this.windowHeight = Math.max(1, windowHeight);
        }

        McpCamera.Projection project(double x, double y, double z) {
            if (!ui) {
                return camera.project(x, y, z, width, height);
            }
            McpCamera.Projection p = camera.project(x, y, z, windowWidth, windowHeight);
            double cropX = 0.0;
            double cropY = 0.0;
            double cropW = windowWidth;
            double cropH = windowHeight;
            double srcAspect = (double) windowWidth / windowHeight;
            double dstAspect = (double) width / height;
            if (srcAspect > dstAspect) {
                cropW = windowHeight * dstAspect;
                cropX = (windowWidth - cropW) / 2.0;
            } else if (srcAspect < dstAspect) {
                cropH = windowWidth / dstAspect;
                cropY = (windowHeight - cropH) / 2.0;
            }
            double sx = (p.screenX() - cropX) * width / cropW;
            double sy = (p.screenY() - cropY) * height / cropH;
            boolean on = p.inFront() && sx >= 0.0 && sx <= width && sy >= 0.0 && sy <= height;
            return new McpCamera.Projection(sx, sy, p.depth(), p.distance(), p.inFront(), on);
        }

        void put(JsonObject t, double x, double y, double z) {
            McpCamera.Projection p = project(x, y, z);
            t.add("screen", pixel(p));
            t.addProperty("depth", McpCamera.round(p.depth(), 3));
            t.addProperty("distance", McpCamera.round(p.distance(), 3));
            t.addProperty("inFront", p.inFront());
            t.addProperty("onScreen", p.onScreen());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Game thread: runs {@code work} with the replay at {@code tick}, then puts the play head back. With
     * {@code motion} the entities' velocities come from the tick before as well (only when jumping anyway).
     */
    @SuppressWarnings("unchecked")
    static <T> T at(McpObserver observer, McpObserver.Replay r, int tick, boolean motion, Supplier<T> work) throws McpArgs.Invalid {
        if (tick == r.tick()) {
            return work.get();
        }
        if (r.exporting()) {
            throw exporting();
        }
        try {
            if (motion && tick > 0) {
                observer.seek(tick - 1);
                List<McpEntity> before = observer.entities();
                observer.seek(tick);
                T now = work.get();
                if (now instanceof List<?> list) {
                    McpEntityQuery.motionFrom(before, (List<McpEntity>) list);
                }
                return now;
            }
            observer.seek(tick);
            return work.get();
        } finally {
            observer.seek(r.tick());
        }
    }

    static McpObserver.Replay requireReplay(McpObserver observer) throws McpArgs.Invalid {
        McpObserver.Replay r = observer.replay();
        if (r == null) {
            throw noReplay();
        }
        return r;
    }

    private static McpArgs.Invalid noReplay() {
        return new McpArgs.Invalid("No replay is open in the game: open one first (list_replays gives them; get_state tells what "
                + "the game shows).");
    }

    private static McpArgs.Invalid exporting() {
        return new McpArgs.Invalid("A video export or a frame is being drawn: only the play head can be looked at until it is done.");
    }

    private static McpArgs.Invalid notFound(String key, int tick) {
        return new McpArgs.Invalid("No entity '" + key + "' in the world at tick " + tick + " (list_entities gives the ids)");
    }

    /** The tick asked for, the play head when none, checked against the replay's length. */
    static int tick(McpArgs args, McpObserver.Replay r) throws McpArgs.Invalid {
        Integer asked = args.optionalInteger("tick", 0, MAX_TICK);
        if (asked == null) {
            return r.tick();
        }
        checkTick(asked, r.durationTicks(), "tick");
        return asked;
    }

    private static void checkTick(int tick, int duration, String name) throws McpArgs.Invalid {
        if (tick > duration) {
            throw new McpArgs.Invalid("'" + name + "' " + tick + " is past the end of the replay (" + duration + " ticks, "
                    + McpJson.time(duration) + ")");
        }
    }

    static JsonObject replayInfo(McpObserver.Replay r, int tick) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id());
        o.addProperty("name", r.meta() == null || r.meta().name == null || r.meta().name.isEmpty() ? r.id() : r.meta().name);
        o.addProperty("durationTicks", r.durationTicks());
        o.addProperty("duration", McpJson.time(r.durationTicks()));
        o.addProperty("playheadTick", r.tick());
        o.addProperty("playhead", McpJson.time(r.tick()));
        return o;
    }

    private static JsonObject header(McpObserver.Replay r, int tick) {
        JsonObject out = new JsonObject();
        out.add("replay", replayInfo(r, tick));
        out.addProperty("tick", tick);
        out.addProperty("time", McpJson.time(tick));
        return out;
    }
}
