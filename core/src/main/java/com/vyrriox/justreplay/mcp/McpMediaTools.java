package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.WorldSettings;
import com.vyrriox.justreplay.editor.audio.AudioClip;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.editor.fx.FxEffect;
import com.vyrriox.justreplay.editor.fx.FxParam;
import com.vyrriox.justreplay.editor.fx.FxType;
import com.vyrriox.justreplay.editor.overlay.Overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The edit tools of what is heard and seen on top of the world: audio tracks and clips with their volumes and the mix
 * of the game sounds, the effect stack with its keyframes and the fades, text and picture overlays with their
 * keyframes, and the time, weather, fog and particles of the world.
 *
 * @author vyrriox
 */
final class McpMediaTools {
    static final List<String> FADE_CURVES = Arrays.asList(AudioClip.FADE_CURVES);
    static final List<String> ANCHORS = List.of(Overlay.POSITION, Overlay.BLOCK, Overlay.ENTITY);
    static final List<String> FACINGS = List.of(Overlay.BILLBOARD, Overlay.FIXED, Overlay.FLAT);
    static final List<String> FACES = List.of("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST");
    static final List<String> ALIGNS = List.of(Overlay.LEFT, Overlay.CENTER, Overlay.RIGHT);
    static final List<String> WORLD_KEYS = List.of("time", "rain", "thunder");
    /** An audio file is decoded once; a long one takes a few seconds. */
    static final int AUDIO_TIMEOUT_SECONDS = 60;
    private static final int MAX_T = McpEditTools.MAX_T;
    private static final double MAX_C = McpEditTools.MAX_C;

    private McpMediaTools() {
    }

    static List<String> effectTypes() {
        List<String> out = new ArrayList<>();
        for (FxType t : FxType.values()) {
            out.add(t.name());
        }
        return out;
    }

    /** Every effect type with its parameters, for the tool's description. */
    static String effectParams() {
        List<String> out = new ArrayList<>();
        for (FxType t : FxType.values()) {
            List<String> params = new ArrayList<>();
            for (FxParam p : t.params) {
                params.add(p.isChoice() ? p.name() + " (0.." + (p.choices().length - 1) + ": " + String.join(", ", p.choices()) + ")"
                        : p.name() + " " + number(p.min()) + ".." + number(p.max()));
            }
            out.add(t.name() + " [" + String.join(", ", params) + "]");
        }
        return String.join("; ", out);
    }

    private static String number(float f) {
        return f == Math.rint(f) ? Integer.toString((int) f) : String.format(Locale.ROOT, "%s", f);
    }

    // ------------------------------------------------------------------ registration

    static void register(McpRegistry registry, McpObserver observer, McpControl control) {
        McpFields<AudioTrack> track = new McpFields<AudioTrack>()
                .text("name", "Name of the track.", 64, (t, v) -> t.name = v.trim())
                .number("volume", "Volume of the track, 0 to 4 (1 leaves the clips as they are).", 0.0, 4.0, (t, v) -> t.volume = v.floatValue())
                .bool("mute", "Silences the track.", (t, v) -> t.mute = v)
                .bool("solo", "Plays only the solo tracks.", (t, v) -> t.solo = v)
                .bool("crossfade", "Overlapping clips crossfade instead of adding up.", (t, v) -> t.crossfade = v);
        registry.add(McpTool.builder("edit_audio_track")
                .title("Edit an audio track")
                .description("Adds, changes or removes an audio track of the edit (music and sound design mixed over the game "
                        + "sounds). Removing a track removes its clips; the audio files stay.")
                .input(track.addTo(McpSchema.object()
                        .choice("action", "add, update or remove.", null, "add", "update", "remove")
                        .string("track", "update, remove: id or exact name of the track."))
                        .required("action"))
                .destructive()
                .handler((args, context) -> audioTrack(observer, control, track, args))
                .build());
        McpFields<AudioClip> clip = clipFields();
        registry.add(McpTool.builder("edit_audio_clip")
                .title("Edit an audio clip")
                .description("Adds, changes, moves to another track or removes a clip of an audio file (list_files kind=audio) "
                        + "on a track. A clip starts at a timeline tick ('start', the play head for a new one) and plays its "
                        + "file from 'in' to 'out' (source ticks, the whole file by default), at a volume with fades, a speed "
                        + "and an optional loop. A new clip goes on 'track', else the first track (made when there is none). "
                        + "The file is decoded first, which takes a moment for a long one.")
                .input(clip.addTo(McpSchema.object()
                        .choice("action", "add, update, move or remove.", null, "add", "update", "move", "remove")
                        .string("clip", "update, move, remove: id of the clip.")
                        .string("track", "add: the track it goes on. move: the track it moves to. Id or exact name.")
                        .string("file", "add: a file of the audio folder (list_files kind=audio)."))
                        .required("action"))
                .destructive()
                .offGameThread()
                .timeoutSeconds(AUDIO_TIMEOUT_SECONDS)
                .handler((args, context) -> audioClip(observer, control, clip, args, context))
                .build());
        registry.add(McpTool.builder("set_audio_mix")
                .title("Set the sound mix")
                .description("Sets the mix of the recorded sounds under the audio tracks: their volume, the volume of each kind "
                        + "of game sound (" + String.join(", ", control.soundKinds()) + "), muting them, the recorded voice "
                        + "chat, and ducking (game sounds and voices lowered while a clip plays).")
                .input(McpSchema.object()
                        .number("gameVolume", "Volume of the recorded game sounds, 0 to 1.", 0.0, 1.0)
                        .numbers("soundGains", "Volume of kinds of game sounds, 0 to 4, such as {\"footsteps\": 0.5}; null for "
                                + "the default of a kind.")
                        .bool("muteGame", "Silences the recorded game sounds (the voices keep their own switch).")
                        .bool("voices", "Plays the recorded voice chat and puts it in the videos.")
                        .bool("ducking", "Lowers the game sounds and voices while an audio clip plays.")
                        .number("duckAmount", "How much ducking lowers them, 0 to 1.", 0.0, 1.0))
                .destructive()
                .idempotent()
                .handler((args, context) -> mix(observer, control, args))
                .build());
        registry.add(McpTool.builder("edit_effect")
                .title("Edit an effect")
                .description("Adds, changes, moves in the stack or removes a video effect, or sets and removes keyframes of its "
                        + "parameters. Effects apply in the order of the stack, to every camera or only while one camera is "
                        + "live. 'values' sets fixed values; an animated parameter follows its keyframes (set_key). Types and "
                        + "parameters: " + effectParams() + ".")
                .input(McpSchema.object()
                        .choice("action", "add, update, move, remove, set_key or remove_key.", null, "add", "update", "move",
                                "remove", "set_key", "remove_key")
                        .string("effect", "Id of the effect (all actions but add).")
                        .choice("type", "add: the type of effect.", null, effectTypes().toArray(new String[0]))
                        .string("camera", "Id or exact name of the camera it is bound to, or an empty string for every camera.")
                        .bool("enabled", "Switches the effect on or off.")
                        .numbers("values", "Fixed values of parameters, such as {\"amount\": 0.4}.")
                        .string("file", "LUT effects: a file of the luts folder (list_files kind=luts).")
                        .integer("index", "add, move: position in the stack, 0 first (the end when left out).", 0, 1000)
                        .string("param", "set_key, remove_key: the parameter.")
                        .integer("tick", "set_key, remove_key: tick of the keyframe (the play head when left out).", 0, MAX_T)
                        .number("value", "set_key: value of the parameter at the tick.", -1.0E6, 1.0E6)
                        .bool("ease", "set_key: smooths the change that starts at this keyframe.")
                        .required("action"))
                .destructive()
                .handler((args, context) -> effect(observer, control, args))
                .build());
        registry.add(McpTool.builder("set_effects")
                .title("Set the effect chain and fades")
                .description("Switches the whole effect chain on or off (filters, transitions and fades) and sets the fades from "
                        + "and to black (or white) at the start and end of an exported video.")
                .input(McpSchema.object()
                        .bool("effectsEnabled", "Renders the effects, transitions and fades.")
                        .integer("fadeIn", "Fade in at the start of the export, in ticks.", 0, 1200)
                        .integer("fadeOut", "Fade out at the end of the export, in ticks.", 0, 1200)
                        .bool("fadeWhite", "The fades go through white instead of black."))
                .destructive()
                .idempotent()
                .handler((args, context) -> effects(observer, control, args))
                .build());
        McpFields<Overlay> overlay = overlayFields(control);
        registry.add(McpTool.builder("edit_overlay")
                .title("Edit an overlay")
                .description("Adds, changes or removes a text or picture overlay placed in the world (at a position, on a block "
                        + "face, or following an entity), shown from 'start' to 'end', or sets and removes keyframes of its "
                        + "animated parameters (offset_x, offset_y, offset_z, scale, yaw, pitch, roll, opacity). A new overlay "
                        + "shows for 5 seconds from the play head, 3 blocks in front of the view when no position is given.")
                .input(overlay.addTo(McpSchema.object()
                        .choice("action", "add, update, remove, set_key or remove_key.", null, "add", "update", "remove",
                                "set_key", "remove_key")
                        .string("overlay", "Id or exact name of the overlay (all actions but add).")
                        .choice("type", "add: TEXT (default) or IMAGE.", null, Overlay.TEXT, Overlay.IMAGE))
                        .number("x", "POSITION: X in the world.", -MAX_C, MAX_C)
                        .number("y", "POSITION: Y in the world.", -MAX_C, MAX_C)
                        .number("z", "POSITION: Z in the world.", -MAX_C, MAX_C)
                        .point("block", "BLOCK: the block [x, y, z] whose face holds it.")
                        .string("entity", "ENTITY: the entity it follows (list_entities gives the ids), actor:<id>, or recorder.")
                        .numbers("values", "Fixed values of the animated parameters, such as {\"scale\": 2}.")
                        .string("param", "set_key, remove_key: offset_x, offset_y, offset_z, scale, yaw, pitch, roll or opacity.")
                        .integer("tick", "set_key, remove_key: tick of the keyframe (the play head when left out).", 0, MAX_T)
                        .number("value", "set_key: value of the parameter at the tick.", -1.0E6, 1.0E6)
                        .bool("ease", "set_key: smooths the change that starts at this keyframe.")
                        .required("action"))
                .destructive()
                .handler((args, context) -> overlay(observer, control, overlay, args))
                .build());
        McpFields<WorldSettings> world = worldFields();
        registry.add(McpTool.builder("set_world")
                .title("Set the world")
                .description("Sets the time of day, moon, weather, fog, night vision and particles shown over the recording "
                        + "(RECORDED keeps what was recorded), or keyframes the time, rain or thunder (set_key, remove_key, "
                        + "clear_keys). A time keyframe fixes the time, as the World tab does.")
                .input(world.addTo(McpSchema.object()
                        .choice("action", "set (default), set_key, remove_key or clear_keys.", "set", "set", "set_key", "remove_key",
                                "clear_keys"))
                        .choice("key", "set_key, remove_key, clear_keys: time, rain or thunder.", null, WORLD_KEYS.toArray(new String[0]))
                        .integer("tick", "set_key, remove_key: tick of the keyframe (the play head when left out).", 0, MAX_T)
                        .number("value", "set_key: time of day (0 to 24000) or strength (0 to 1).", 0.0, WorldSettings.DAY))
                .destructive()
                .handler((args, context) -> world(observer, control, world, args))
                .build());
    }

    // ------------------------------------------------------------------ audio tracks

    static AudioTrack track(ReplayProject p, String key) throws McpArgs.Invalid {
        AudioTrack found = null;
        for (AudioTrack t : p.audioTracks) {
            if (t.id.equals(key)) {
                return t;
            }
            if (t.name != null && t.name.equalsIgnoreCase(key)) {
                if (found != null) {
                    throw new McpArgs.Invalid("Several audio tracks are named '" + key + "': use its id");
                }
                found = t;
            }
        }
        if (found == null) {
            throw new McpArgs.Invalid("No audio track has the id or name '" + key + "' (get_project with sections=audio gives them)");
        }
        return found;
    }

    private static JsonObject trackJson(AudioTrack t) {
        JsonObject o = McpJson.summarize(McpJson.tree(t)).getAsJsonObject();
        o.addProperty("clipCount", t.clips.size());
        return o;
    }

    static McpToolResult audioTrack(McpObserver observer, McpControl control, McpFields<AudioTrack> fields, McpArgs args)
            throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("add", "update", "remove"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    McpEditTools.only(args, action, fields.names());
                    AudioTrack t = new AudioTrack(p.nextTrackName());
                    fields.read(args).applyTo(t);
                    if (t.name.isEmpty()) {
                        t.name = p.nextTrackName();
                    }
                    p.audioTracks.add(t);
                    out.add("track", trackJson(t));
                }
                case "update" -> {
                    McpEditTools.only(args, action, McpEditTools.names("track", fields.names()));
                    AudioTrack t = track(p, args.required("track"));
                    McpFields.Changes<AudioTrack> given = fields.read(args);
                    if (given.isEmpty()) {
                        throw McpEditTools.nothing();
                    }
                    given.applyTo(t);
                    if (t.name.isEmpty()) {
                        t.name = p.nextTrackName();
                    }
                    out.add("track", trackJson(t));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("track"));
                    AudioTrack t = track(p, args.required("track"));
                    p.audioTracks.remove(t);
                    out.addProperty("removed", t.id);
                    out.addProperty("name", t.name);
                    out.addProperty("clipsRemoved", t.clips.size());
                }
            }
            out.addProperty("tracks", p.audioTracks.size());
            return out;
        });
    }

    // ------------------------------------------------------------------ audio clips

    private static McpFields<AudioClip> clipFields() {
        return new McpFields<AudioClip>()
                .integer("start", "Timeline tick where the clip starts (the play head for a new clip).", 0, MAX_T, (c, v) -> c.start = v)
                .number("in", "First source tick played (trim).", 0.0, MAX_T, (c, v) -> c.in = v)
                .number("out", "Source tick where it stops, or wraps when looping (the end of the file for a new clip).", 0.0, MAX_T,
                        (c, v) -> c.out = v)
                .number("volume", "Volume, 0 to 4.", 0.0, 4.0, (c, v) -> c.volume = v.floatValue())
                .integer("fadeIn", "Fade in, in ticks from the start of the clip.", 0, MAX_T, (c, v) -> c.fadeIn = v)
                .integer("fadeOut", "Fade out, in ticks before the end of the clip.", 0, MAX_T, (c, v) -> c.fadeOut = v)
                .choice("fadeCurve", "Shape of both fades: LINEAR, EQUAL_POWER or EXPONENTIAL.", FADE_CURVES, (c, v) -> c.fadeCurve = v)
                .bool("loop", "Repeats the trimmed part for 'length' ticks.", (c, v) -> c.loop = v)
                .integer("length", "Looping clips: length on the timeline, in ticks.", 1, MAX_T, (c, v) -> c.length = v)
                .number("offset", "Looping clips: source position at the start of the clip, in ticks after 'in'.", 0.0, MAX_T,
                        (c, v) -> c.offset = v)
                .number("speed", "Playback rate, 0.5 to 2; the pitch follows.", AudioClip.MIN_SPEED, AudioClip.MAX_SPEED,
                        (c, v) -> c.speed = v.floatValue());
    }

    /** Where a clip is, found on the game thread. */
    private record Located(AudioTrack track, AudioClip clip) {
    }

    private static Located clip(ReplayProject p, String id) throws McpArgs.Invalid {
        for (AudioTrack t : p.audioTracks) {
            for (AudioClip c : t.clips) {
                if (c.id.equals(id)) {
                    return new Located(t, c);
                }
            }
        }
        throw new McpArgs.Invalid("No audio clip has the id '" + id + "' (get_project with sections=audio gives them)");
    }

    /** What the game thread prepared for a clip change: the file to decode, or none. */
    private record Plan(String file, boolean decode) {
    }

    static McpToolResult audioClip(McpObserver observer, McpControl control, McpFields<AudioClip> fields, McpArgs args, McpContext context)
            throws Exception {
        String action = McpEditTools.action(args, List.of("add", "update", "move", "remove"));
        Set<String> allowed = switch (action) {
            case "add" -> McpEditTools.names("track", "file", fields.names());
            case "update" -> McpEditTools.names("clip", fields.names());
            case "move" -> Set.of("clip", "track");
            default -> Set.of("clip");
        };
        McpEditTools.only(args, action, allowed);
        McpFields.Changes<AudioClip> given = fields.read(args);
        Plan plan = context.onGameThread(() -> {
            McpObserver.Replay r = McpControlTools.editable(observer);
            ReplayProject p = r.project();
            if (action.equals("add")) {
                String file = args.required("file");
                if (!control.files("audio").contains(file)) {
                    throw new McpArgs.Invalid("No file '" + file + "' in the audio folder (list_files kind=audio gives them)");
                }
                if (args.has("track")) {
                    track(p, args.required("track"));
                }
                return new Plan(file, true);
            }
            Located at = clip(p, args.required("clip"));
            if (action.equals("move")) {
                track(p, args.required("track"));
            }
            boolean trims = given.has("in") || given.has("out") || given.has("offset") || given.has("loop") || given.has("length");
            return new Plan(at.clip().file, action.equals("update") && trims);
        });
        double sourceTicks = -1;
        if (plan.decode()) {
            try {
                sourceTicks = control.audioLength(plan.file()).get(Math.max(1L, context.remainingMillis() - 500L), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new McpArgs.Invalid("Audio file '" + plan.file() + "' cannot be played: " + McpProtocol.describe(cause));
            } catch (java.util.concurrent.TimeoutException e) {
                throw new McpArgs.Invalid("Audio file '" + plan.file() + "' is still being decoded: call again in a moment");
            }
        }
        double length = sourceTicks;
        return context.onGameThread(() -> McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    AudioClip c = new AudioClip(plan.file(), r.tick(), length);
                    given.applyTo(c);
                    checkClip(c, length);
                    AudioTrack t = args.has("track") ? track(p, args.required("track"))
                            : p.audioTracks.isEmpty() ? null : p.audioTracks.get(0);
                    if (t == null) {
                        t = new AudioTrack(p.nextTrackName());
                        p.audioTracks.add(t);
                        out.addProperty("trackAdded", true);
                    }
                    c.clampFades();
                    t.clips.add(c);
                    t.sort();
                    out.add("clip", clipJson(c, t));
                }
                case "update" -> {
                    Located at = clip(p, args.required("clip"));
                    if (given.isEmpty()) {
                        throw McpEditTools.nothing();
                    }
                    AudioClip trial = McpEditTools.copy(at.clip());
                    given.applyTo(trial);
                    checkClip(trial, length);
                    given.applyTo(at.clip());
                    at.clip().clampFades();
                    at.track().sort();
                    out.add("clip", clipJson(at.clip(), at.track()));
                }
                case "move" -> {
                    Located at = clip(p, args.required("clip"));
                    AudioTrack to = track(p, args.required("track"));
                    at.track().clips.remove(at.clip());
                    to.clips.add(at.clip());
                    to.sort();
                    out.add("clip", clipJson(at.clip(), to));
                }
                default -> {
                    Located at = clip(p, args.required("clip"));
                    at.track().clips.remove(at.clip());
                    out.addProperty("removed", at.clip().id);
                    out.addProperty("track", at.track().name);
                }
            }
            return out;
        }));
    }

    /** A clip must play something of its file. */
    private static void checkClip(AudioClip c, double sourceTicks) throws McpArgs.Invalid {
        if (c.out <= c.in) {
            throw new McpArgs.Invalid("'out' (" + c.out + ") must be after 'in' (" + c.in + ")");
        }
        if (sourceTicks >= 0 && c.out > sourceTicks + 1.0E-6) {
            throw new McpArgs.Invalid("'out' (" + c.out + ") is past the end of the file (" + McpCamera.round(sourceTicks, 3) + " ticks)");
        }
        if (c.loop && c.offset >= c.span()) {
            throw new McpArgs.Invalid("'offset' must be shorter than the trimmed part (" + McpCamera.round(c.span(), 3) + " ticks)");
        }
    }

    private static JsonObject clipJson(AudioClip c, AudioTrack t) {
        JsonObject o = McpJson.tree(c).getAsJsonObject();
        o.addProperty("trackId", t.id);
        o.addProperty("trackName", t.name);
        o.addProperty("startTime", McpJson.time(c.start));
        o.addProperty("timelineTicks", McpCamera.round(c.length(), 3));
        return o;
    }

    static McpToolResult mix(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        if (args.names().isEmpty()) {
            throw McpEditTools.nothing();
        }
        JsonObject gains = args.object("soundGains");
        if (gains != null) {
            for (Map.Entry<String, JsonElement> e : gains.entrySet()) {
                if (!control.soundKinds().contains(e.getKey())) {
                    throw new McpArgs.Invalid("'" + e.getKey() + "' is not a kind of game sound: " + String.join(", ", control.soundKinds()));
                }
                JsonElement v = e.getValue();
                if (!v.isJsonNull() && !(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() >= 0.0
                        && v.getAsDouble() <= 4.0)) {
                    throw new McpArgs.Invalid("The volume of '" + e.getKey() + "' must be a number from 0 to 4, or null for its default");
                }
            }
        }
        Double gameVolume = args.number("gameVolume", 0.0, 1.0);
        Double duck = args.number("duckAmount", 0.0, 1.0);
        boolean muteGame = args.bool("muteGame", false);
        boolean voices = args.bool("voices", true);
        boolean ducking = args.bool("ducking", false);
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            if (gameVolume != null) {
                p.gameVolume = gameVolume.floatValue();
            }
            if (gains != null) {
                for (Map.Entry<String, JsonElement> e : gains.entrySet()) {
                    if (e.getValue().isJsonNull()) {
                        p.soundGains.remove(e.getKey());
                    } else {
                        p.soundGains.put(e.getKey(), e.getValue().getAsFloat());
                    }
                }
            }
            if (args.has("muteGame")) {
                p.muteGame = muteGame;
            }
            if (args.has("voices")) {
                p.voices = voices;
            }
            if (args.has("ducking")) {
                p.ducking = ducking;
            }
            if (duck != null) {
                p.duckAmount = duck.floatValue();
            }
            JsonObject out = new JsonObject();
            out.addProperty("gameVolume", p.gameVolume);
            out.add("soundGains", McpJson.tree(p.soundGains));
            out.addProperty("muteGame", p.muteGame);
            out.addProperty("voices", p.voices);
            out.addProperty("ducking", p.ducking);
            out.addProperty("duckAmount", p.duckAmount);
            return out;
        });
    }

    // ------------------------------------------------------------------ effects

    private static FxEffect effectById(ReplayProject p, String id) throws McpArgs.Invalid {
        for (FxEffect e : p.effects) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        throw new McpArgs.Invalid("No effect has the id '" + id + "' (get_project with sections=effects gives them)");
    }

    private static FxParam param(FxType type, String name) throws McpArgs.Invalid {
        FxParam p = type.param(name);
        if (p == null) {
            List<String> names = new ArrayList<>();
            for (FxParam q : type.params) {
                names.add(q.name());
            }
            throw new McpArgs.Invalid(type.name() + " has no parameter '" + name + "' (its parameters: " + String.join(", ", names) + ")");
        }
        return p;
    }

    /** Checks a value against a parameter's range (or the index of one of its choices). */
    static float checkValue(FxParam p, double value, String what) throws McpArgs.Invalid {
        if (!Double.isFinite(value) || value < p.min() - 1.0E-6 || value > p.max() + 1.0E-6) {
            throw new McpArgs.Invalid(what + " '" + p.name() + "' must be from " + number(p.min()) + " to " + number(p.max())
                    + (p.isChoice() ? " (0.." + (p.choices().length - 1) + ": " + String.join(", ", p.choices()) + ")" : ""));
        }
        if (p.isChoice() && value != Math.rint(value)) {
            throw new McpArgs.Invalid(what + " '" + p.name() + "' takes the index of a choice, a whole number");
        }
        return (float) value;
    }

    /** The {@code values} argument checked against the parameters: name to value. */
    private static Map<FxParam, Float> values(McpArgs args, List<FxParam> params, String owner) throws McpArgs.Invalid {
        JsonObject values = args.object("values");
        Map<FxParam, Float> out = new java.util.LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            FxParam p = null;
            for (FxParam q : params) {
                if (q.name().equals(e.getKey())) {
                    p = q;
                }
            }
            if (p == null) {
                List<String> names = new ArrayList<>();
                params.forEach(q -> names.add(q.name()));
                throw new McpArgs.Invalid(owner + " has no parameter '" + e.getKey() + "' (its parameters: " + String.join(", ", names) + ")");
            }
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                throw new McpArgs.Invalid("The value of '" + e.getKey() + "' must be a number");
            }
            out.put(p, checkValue(p, v.getAsDouble(), "Value of"));
        }
        return out;
    }

    static McpToolResult effect(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("add", "update", "move", "remove", "set_key", "remove_key"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    McpEditTools.only(args, action, Set.of("type", "camera", "enabled", "values", "file", "index"));
                    if (!args.has("type")) {
                        throw new McpArgs.Invalid("Argument 'type' is required: " + String.join(", ", effectTypes()));
                    }
                    FxType type = FxType.valueOf(args.choice("type", null, effectTypes()));
                    FxEffect e = new FxEffect(type, cameraId(p, args));
                    effectFields(control, args, e, type);
                    int index = args.integer("index", p.effects.size(), 0, 1000);
                    p.effects.add(Math.min(index, p.effects.size()), e);
                    out.add("effect", effectJson(e, p));
                }
                case "update" -> {
                    McpEditTools.only(args, action, Set.of("effect", "camera", "enabled", "values", "file"));
                    FxEffect e = effectById(p, args.required("effect"));
                    if (args.names().size() <= 2) {
                        throw McpEditTools.nothing();
                    }
                    FxType type = known(e);
                    String camera = args.has("camera") ? cameraId(p, args) : e.camera;
                    effectFields(control, args, McpEditTools.copy(e), type);
                    effectFields(control, args, e, type);
                    e.camera = camera;
                    out.add("effect", effectJson(e, p));
                }
                case "move" -> {
                    McpEditTools.only(args, action, Set.of("effect", "index"));
                    FxEffect e = effectById(p, args.required("effect"));
                    if (!args.has("index")) {
                        throw new McpArgs.Invalid("Argument 'index' is required: the new position in the stack, 0 first");
                    }
                    int index = args.integer("index", 0, 0, 1000);
                    p.effects.remove(e);
                    p.effects.add(Math.min(index, p.effects.size()), e);
                    out.add("effect", effectJson(e, p));
                }
                case "remove" -> {
                    McpEditTools.only(args, action, Set.of("effect"));
                    FxEffect e = effectById(p, args.required("effect"));
                    p.effects.remove(e);
                    out.addProperty("removed", e.id);
                    out.addProperty("type", e.type);
                }
                case "set_key" -> {
                    McpEditTools.only(args, action, Set.of("effect", "param", "tick", "value", "ease"));
                    FxEffect e = effectById(p, args.required("effect"));
                    FxParam param = param(known(e), args.required("param"));
                    if (!param.keyframeable()) {
                        throw new McpArgs.Invalid("'" + param.name() + "' is a choice: it is set with values, not keyframed");
                    }
                    if (!args.has("value")) {
                        throw new McpArgs.Invalid("Argument 'value' is required");
                    }
                    float value = checkValue(param, args.number("value", 0.0, -1.0E6, 1.0E6), "Value of");
                    FxEffect.Key key = e.setKey(param, McpControlTools.tick(args, "tick", r, r.tick()), value);
                    if (args.has("ease")) {
                        key.ease = args.bool("ease", true);
                    }
                    out.add("effect", effectJson(e, p));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("effect", "param", "tick"));
                    FxEffect e = effectById(p, args.required("effect"));
                    FxParam param = param(known(e), args.required("param"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    FxEffect.Key key = e.keyAt(param, tick);
                    if (key == null) {
                        throw new McpArgs.Invalid("'" + param.name() + "' has no keyframe at tick " + tick);
                    }
                    e.removeKey(param, key);
                    out.add("effect", effectJson(e, p));
                }
            }
            out.addProperty("effects", p.effects.size());
            return out;
        });
    }

    private static FxType known(FxEffect e) throws McpArgs.Invalid {
        FxType type = e.fxType();
        if (type == null) {
            throw new McpArgs.Invalid("Effect '" + e.id + "' has a type this version does not know (" + e.type + ")");
        }
        return type;
    }

    private static String cameraId(ReplayProject p, McpArgs args) throws McpArgs.Invalid {
        String key = args.string("camera", "");
        return key.isBlank() ? "" : McpEditTools.camera(p, key.trim()).id;
    }

    private static void effectFields(McpControl control, McpArgs args, FxEffect e, FxType type) throws McpArgs.Invalid {
        if (args.has("enabled")) {
            e.enabled = args.bool("enabled", true);
        }
        for (Map.Entry<FxParam, Float> v : values(args, Arrays.asList(type.params), type.name()).entrySet()) {
            e.values.put(v.getKey().name(), v.getValue());
        }
        if (args.has("file")) {
            if (type != FxType.LUT) {
                throw new McpArgs.Invalid("'file' is for LUT effects");
            }
            String file = args.text("file", 256).trim();
            if (!file.isEmpty() && !control.files("luts").contains(file)) {
                throw new McpArgs.Invalid("No file '" + file + "' in the luts folder (list_files kind=luts gives them)");
            }
            e.file = file;
        }
    }

    private static JsonObject effectJson(FxEffect e, ReplayProject p) {
        JsonObject o = McpJson.tree(e).getAsJsonObject();
        o.addProperty("index", p.effects.indexOf(e));
        ReplayProject.Camera c = e.isGlobal() ? null : p.camera(e.camera);
        o.addProperty("cameraName", c == null ? "" : c.name);
        return o;
    }

    static McpToolResult effects(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        if (args.names().isEmpty()) {
            throw McpEditTools.nothing();
        }
        boolean enabled = args.bool("effectsEnabled", true);
        int fadeIn = args.integer("fadeIn", 0, 0, 1200);
        int fadeOut = args.integer("fadeOut", 0, 0, 1200);
        boolean white = args.bool("fadeWhite", false);
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            if (args.has("effectsEnabled")) {
                p.effectsEnabled = enabled;
            }
            if (args.has("fadeIn")) {
                p.fadeIn = fadeIn;
            }
            if (args.has("fadeOut")) {
                p.fadeOut = fadeOut;
            }
            if (args.has("fadeWhite")) {
                p.fadeWhite = white;
            }
            JsonObject out = new JsonObject();
            out.addProperty("effectsEnabled", p.effectsEnabled);
            out.addProperty("fadeIn", p.fadeIn);
            out.addProperty("fadeOut", p.fadeOut);
            out.addProperty("fadeWhite", p.fadeWhite);
            return out;
        });
    }

    // ------------------------------------------------------------------ overlays

    private static McpFields<Overlay> overlayFields(McpControl control) {
        return new McpFields<Overlay>()
                .text("name", "Name shown in the Overlays tab.", 64, (o, v) -> o.name = v.trim())
                .bool("visible", "Shows the overlay.", (o, v) -> o.visible = v)
                .choice("anchor", "POSITION (x, y, z), BLOCK (a face of 'block') or ENTITY (follows 'entity').", ANCHORS, (o, v) -> o.anchor = v)
                .choice("face", "BLOCK: the face of the block it lies on.", FACES, (o, v) -> o.face = v)
                .choice("facing", "BILLBOARD turns to the camera, FIXED keeps its yaw and pitch, FLAT lies on the block face (BLOCK only).",
                        FACINGS, (o, v) -> o.facing = v)
                .bool("throughWalls", "Drawn through walls.", (o, v) -> o.throughWalls = v)
                .integer("start", "First tick shown.", 0, MAX_T, (o, v) -> o.start = v)
                .integer("end", "Tick it disappears.", 1, MAX_T, (o, v) -> o.end = v)
                .integer("fadeIn", "Fade in, in ticks.", 0, 1200, (o, v) -> o.fadeIn = v)
                .integer("fadeOut", "Fade out, in ticks.", 0, 1200, (o, v) -> o.fadeOut = v)
                .text("text", "TEXT: the text, \\n for a new line; formatting codes with the section sign are kept.", 2000, (o, v) -> o.text = v)
                .number("fontSize", "TEXT: size of a line in blocks.", 0.01, 16.0, (o, v) -> o.fontSize = v.floatValue())
                .color("color", "TEXT: colour of the text, #RRGGBB.", (o, v) -> o.color = v)
                .choice("align", "TEXT: LEFT, CENTER or RIGHT.", ALIGNS, (o, v) -> o.align = v)
                .bool("shadow", "TEXT: drop shadow.", (o, v) -> o.shadow = v)
                .bool("outline", "TEXT: outline.", (o, v) -> o.outline = v)
                .color("outlineColor", "TEXT: colour of the outline, #RRGGBB.", (o, v) -> o.outlineColor = v)
                .bool("plate", "TEXT: a plate behind the text.", (o, v) -> o.plate = v)
                .color("plateColor", "TEXT: colour of the plate, #RRGGBB.", (o, v) -> o.plateColor = v)
                .number("plateOpacity", "TEXT: opacity of the plate, 0 to 1.", 0.0, 1.0, (o, v) -> o.plateOpacity = v.floatValue())
                .number("padding", "TEXT: room around the text on the plate.", 0.0, 32.0, (o, v) -> o.padding = v.floatValue())
                .custom("image", s -> s.string("image", "IMAGE: a file of the images folder (list_files kind=images)."),
                        (args, name) -> {
                            String file = args.text(name, 256).trim();
                            if (!control.files("images").contains(file)) {
                                throw new McpArgs.Invalid("No file '" + file + "' in the images folder (list_files kind=images gives them)");
                            }
                            return file;
                        }, (Overlay o, String v) -> o.image = v)
                .number("width", "IMAGE: width in blocks.", 0.05, 256.0, (o, v) -> o.width = v.floatValue())
                .number("height", "IMAGE: height in blocks (with keepAspect=false).", 0.05, 256.0, (o, v) -> o.height = v.floatValue())
                .bool("keepAspect", "IMAGE: keeps the picture's shape.", (o, v) -> o.keepAspect = v)
                .color("tint", "IMAGE: colour multiplied with the picture, #RRGGBB.", (o, v) -> o.tint = v);
    }

    private static final Set<String> TEXT_ONLY = Set.of("text", "fontSize", "color", "align", "shadow", "outline", "outlineColor",
            "plate", "plateColor", "plateOpacity", "padding");
    private static final Set<String> IMAGE_ONLY = Set.of("image", "width", "height", "keepAspect", "tint");
    private static final Set<String> PLACE = Set.of("x", "y", "z", "block", "entity");

    static Overlay overlayByKey(ReplayProject p, String key) throws McpArgs.Invalid {
        Overlay found = null;
        for (Overlay o : p.overlays) {
            if (o.id.equals(key)) {
                return o;
            }
            if (o.name != null && !o.name.isEmpty() && o.name.equalsIgnoreCase(key)) {
                if (found != null) {
                    throw new McpArgs.Invalid("Several overlays are named '" + key + "': use its id");
                }
                found = o;
            }
        }
        if (found == null) {
            throw new McpArgs.Invalid("No overlay has the id or name '" + key + "' (get_project with sections=overlays gives them)");
        }
        return found;
    }

    static McpToolResult overlay(McpObserver observer, McpControl control, McpFields<Overlay> fields, McpArgs args) throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("add", "update", "remove", "set_key", "remove_key"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    McpEditTools.only(args, action, McpEditTools.names(fields.names(), "type", PLACE, "values"));
                    Overlay o = new Overlay(args.choice("type", Overlay.TEXT, List.of(Overlay.TEXT, Overlay.IMAGE)));
                    // The Overlays tab's defaults: five seconds from the play head, within the replay.
                    int duration = Math.max(1, r.durationTicks());
                    o.start = Math.min(r.tick(), Math.max(0, duration - 1));
                    o.end = Math.min(duration, o.start + 100);
                    if (o.end - o.start < 20) {
                        o.start = Math.max(0, o.end - 100);
                    }
                    o.end = Math.max(o.end, o.start + 1);
                    if (o.isImage()) {
                        List<String> images = control.files("images");
                        o.image = images.isEmpty() ? "" : images.get(0);
                    }
                    McpCamera view = observer.currentCamera();
                    double[] ahead = view.forward();
                    o.x = view.x() + ahead[0] * 3.0;
                    o.y = view.y() + ahead[1] * 3.0;
                    o.z = view.z() + ahead[2] * 3.0;
                    writeOverlay(observer, r, args, fields, o);
                    if (o.isImage() && o.image.isEmpty()) {
                        throw new McpArgs.Invalid("An IMAGE overlay needs 'image', and the images folder is empty");
                    }
                    p.overlays.add(o);
                    out.add("overlay", overlayJson(o));
                }
                case "update" -> {
                    McpEditTools.only(args, action, McpEditTools.names("overlay", fields.names(), PLACE, "values"));
                    Overlay o = overlayByKey(p, args.required("overlay"));
                    if (args.names().size() <= 2) {
                        throw McpEditTools.nothing();
                    }
                    writeOverlay(observer, r, args, fields, McpEditTools.copy(o));
                    writeOverlay(observer, r, args, fields, o);
                    out.add("overlay", overlayJson(o));
                }
                case "remove" -> {
                    McpEditTools.only(args, action, Set.of("overlay"));
                    Overlay o = overlayByKey(p, args.required("overlay"));
                    p.overlays.remove(o);
                    out.addProperty("removed", o.id);
                    out.addProperty("name", o.displayName());
                }
                case "set_key" -> {
                    McpEditTools.only(args, action, Set.of("overlay", "param", "tick", "value", "ease"));
                    Overlay o = overlayByKey(p, args.required("overlay"));
                    FxParam param = overlayParam(args.required("param"));
                    if (!args.has("value")) {
                        throw new McpArgs.Invalid("Argument 'value' is required");
                    }
                    float value = checkValue(param, args.number("value", 0.0, -1.0E6, 1.0E6), "Value of");
                    FxEffect.Key key = o.setKey(param, McpControlTools.tick(args, "tick", r, r.tick()), value);
                    if (args.has("ease")) {
                        key.ease = args.bool("ease", true);
                    }
                    out.add("overlay", overlayJson(o));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("overlay", "param", "tick"));
                    Overlay o = overlayByKey(p, args.required("overlay"));
                    FxParam param = overlayParam(args.required("param"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    FxEffect.Key key = o.keyAt(param, tick);
                    if (key == null) {
                        throw new McpArgs.Invalid("'" + param.name() + "' has no keyframe at tick " + tick);
                    }
                    o.removeKey(param, key);
                    out.add("overlay", overlayJson(o));
                }
            }
            out.addProperty("overlays", p.overlays.size());
            return out;
        });
    }

    private static FxParam overlayParam(String name) throws McpArgs.Invalid {
        for (FxParam p : Overlay.PARAMS) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        List<String> names = new ArrayList<>();
        Overlay.PARAMS.forEach(p -> names.add(p.name()));
        throw new McpArgs.Invalid("Overlays have no parameter '" + name + "' (" + String.join(", ", names) + ")");
    }

    /** Writes the fields, the place and the values of an overlay, checking them as the model would repair them. */
    private static void writeOverlay(McpObserver observer, McpObserver.Replay r, McpArgs args, McpFields<Overlay> fields, Overlay o)
            throws McpArgs.Invalid {
        McpFields.Changes<Overlay> given = fields.read(args);
        given.applyTo(o);
        for (String name : args.names()) {
            if (TEXT_ONLY.contains(name) && !o.isText()) {
                throw new McpArgs.Invalid("'" + name + "' is for TEXT overlays");
            }
            if (IMAGE_ONLY.contains(name) && !o.isImage()) {
                throw new McpArgs.Invalid("'" + name + "' is for IMAGE overlays");
            }
        }
        boolean xyz = args.has("x") || args.has("y") || args.has("z");
        if (xyz) {
            if (!(args.has("x") && args.has("y") && args.has("z"))) {
                throw new McpArgs.Invalid("Give x, y and z together");
            }
            if (!given.has("anchor")) {
                o.anchor = Overlay.POSITION;
            }
            o.x = args.number("x", 0.0, -MAX_C, MAX_C);
            o.y = args.number("y", 0.0, -MAX_C, MAX_C);
            o.z = args.number("z", 0.0, -MAX_C, MAX_C);
        }
        double[] block = args.point("block");
        if (block != null) {
            for (double v : block) {
                if (v != Math.rint(v)) {
                    throw new McpArgs.Invalid("'block' takes whole block coordinates [x, y, z]");
                }
            }
            if (!given.has("anchor")) {
                o.anchor = Overlay.BLOCK;
            }
            o.blockX = (int) block[0];
            o.blockY = (int) block[1];
            o.blockZ = (int) block[2];
        }
        if (args.has("entity")) {
            String key = args.required("entity");
            if (!given.has("anchor")) {
                o.anchor = Overlay.ENTITY;
            }
            if (Overlay.RECORDER.equalsIgnoreCase(key)) {
                o.target = Overlay.RECORDER;
                o.targetName = r.meta() == null || r.meta().recorderName == null ? "" : r.meta().recorderName;
            } else {
                McpEntity e = key.startsWith(Marker.ACTOR_PREFIX) ? null : observer.entity(key);
                if (e != null && e.recorder) {
                    o.target = Overlay.RECORDER;
                    o.targetName = e.name;
                } else {
                    McpEditTools.Ref ref = McpEditTools.entity(observer, r.project(), key, "entity");
                    o.target = ref.id();
                    o.targetName = ref.name();
                }
            }
        }
        if (Overlay.ENTITY.equals(o.anchor) && o.target.isEmpty()) {
            throw new McpArgs.Invalid("An ENTITY overlay needs 'entity', the entity it follows");
        }
        if (Overlay.FLAT.equals(o.facing) && !Overlay.BLOCK.equals(o.anchor)) {
            throw new McpArgs.Invalid("FLAT lies on a block face: it needs anchor=BLOCK");
        }
        if (o.end <= o.start) {
            throw new McpArgs.Invalid("'end' (" + o.end + ") must be after 'start' (" + o.start + ")");
        }
        if (o.start > r.durationTicks()) {
            throw new McpArgs.Invalid("'start' is past the end of the replay (" + r.durationTicks() + " ticks)");
        }
        for (Map.Entry<FxParam, Float> v : values(args, Overlay.PARAMS, "An overlay").entrySet()) {
            o.setBase(v.getKey(), v.getValue());
        }
        o.face = o.face.toUpperCase(Locale.ROOT);
    }

    private static JsonObject overlayJson(Overlay o) {
        JsonObject j = McpJson.tree(o).getAsJsonObject();
        j.addProperty("shownName", o.displayName());
        j.addProperty("startTime", McpJson.time(o.start));
        j.addProperty("endTime", McpJson.time(o.end));
        j.addProperty("color", McpFields.hex(o.color));
        j.addProperty("outlineColor", McpFields.hex(o.outlineColor));
        j.addProperty("plateColor", McpFields.hex(o.plateColor));
        j.addProperty("tint", McpFields.hex(o.tint));
        return j;
    }

    // ------------------------------------------------------------------ world

    private static McpFields<WorldSettings> worldFields() {
        return new McpFields<WorldSettings>()
                .choice("timeMode", "RECORDED keeps the recorded time, FIXED shows 'time' (or its keyframes).",
                        List.of(WorldSettings.TIME_RECORDED, WorldSettings.TIME_FIXED), (w, v) -> w.timeMode = v)
                .number("time", "FIXED: time of day, 0 to 24000 (0 is sunrise, 6000 noon, 18000 midnight).", 0.0, WorldSettings.DAY,
                        (w, v) -> w.time = v.floatValue())
                .integer("moonPhase", "Moon phase 0 to 7, -1 for the recorded one.", -1, 7, (w, v) -> w.moonPhase = v)
                .choice("weather", "RECORDED, CLEAR, RAIN or THUNDER.", WorldSettings.WEATHERS, (w, v) -> w.weather = v)
                .number("rain", "Strength of the rain, 0 to 1.", 0.0, 1.0, (w, v) -> w.rain = v.floatValue())
                .number("thunder", "Strength of the thunder, 0 to 1.", 0.0, 1.0, (w, v) -> w.thunder = v.floatValue())
                .bool("nightVision", "Lights the world as night vision does.", (w, v) -> w.nightVision = v)
                .choice("fog", "NORMAL, REDUCED or OFF.", WorldSettings.FOGS, (w, v) -> w.fog = v)
                .choice("particles", "ALL, NONE, or CHOSEN (all but hiddenParticles).", WorldSettings.PARTICLE_MODES,
                        (w, v) -> w.particles = v)
                .custom("hiddenParticles", s -> s.strings("hiddenParticles", "CHOSEN: particle type ids not drawn, such as "
                        + "minecraft:smoke.", 256), (args, name) -> {
                    List<String> ids = args.strings(name, 256);
                    for (String id : ids) {
                        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                            throw new McpArgs.Invalid("'" + id + "' in 'hiddenParticles' is not a particle id such as minecraft:smoke");
                        }
                    }
                    return ids;
                }, (WorldSettings w, List<String> v) -> w.hiddenParticles = WorldSettings.cleanIds(new ArrayList<>(v)));
    }

    static McpToolResult world(McpObserver observer, McpControl control, McpFields<WorldSettings> fields, McpArgs args) throws McpArgs.Invalid {
        String action = args.choice("action", "set", List.of("set", "set_key", "remove_key", "clear_keys"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            WorldSettings w = r.project().world;
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "set" -> {
                    McpEditTools.only(args, action, fields.names());
                    McpFields.Changes<WorldSettings> given = fields.read(args);
                    if (given.isEmpty()) {
                        throw McpEditTools.nothing();
                    }
                    given.applyTo(w);
                }
                case "set_key" -> {
                    McpEditTools.only(args, action, Set.of("key", "tick", "value"));
                    String key = args.choice("key", null, WORLD_KEYS);
                    if (key == null || !args.has("value")) {
                        throw new McpArgs.Invalid("set_key needs 'key' (time, rain or thunder) and 'value'");
                    }
                    double max = key.equals("time") ? WorldSettings.DAY : 1.0;
                    float value = (float) args.number("value", 0.0, 0.0, max);
                    WorldSettings.setKey(keys(w, key), McpControlTools.tick(args, "tick", r, r.tick()), value);
                    if (key.equals("time")) {
                        w.timeMode = WorldSettings.TIME_FIXED;
                    }
                }
                case "remove_key" -> {
                    McpEditTools.only(args, action, Set.of("key", "tick"));
                    String key = args.choice("key", null, WORLD_KEYS);
                    if (key == null) {
                        throw new McpArgs.Invalid("remove_key needs 'key' (time, rain or thunder)");
                    }
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    if (!keys(w, key).removeIf(k -> k.tick == tick)) {
                        throw new McpArgs.Invalid("No " + key + " keyframe at tick " + tick);
                    }
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("key"));
                    String key = args.choice("key", null, WORLD_KEYS);
                    if (key == null) {
                        throw new McpArgs.Invalid("clear_keys needs 'key' (time, rain or thunder)");
                    }
                    keys(w, key).clear();
                }
            }
            w.normalize();
            out.add("world", McpJson.tree(w));
            return out;
        });
    }

    private static List<FxEffect.Key> keys(WorldSettings w, String key) {
        return switch (key) {
            case "rain" -> w.rainKeys;
            case "thunder" -> w.thunderKeys;
            default -> w.timeKeys;
        };
    }
}
