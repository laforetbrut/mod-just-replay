package com.vyrriox.justreplay.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.audio.AudioClip;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.export.ExportNames;
import com.vyrriox.justreplay.format.ReplayMeta;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON the read tools answer with: the state of the mod, a saved replay, the sections of a project and markers.
 * Field names are those of {@code project.json}, so what a client reads is what the files hold; times come both in
 * ticks (1/20 s) and as text such as {@code 0:10.0}. No path of the computer is ever given, only replay ids.
 *
 * @author vyrriox
 */
public final class McpJson {
    public static final int TICKS_PER_SECOND = 20;
    /** Every section of a project, in the order they are given. */
    public static final List<String> SECTIONS = List.of("overview", "cameras", "cuts", "actors", "actions", "audio",
            "effects", "overlays", "world", "speed", "entities");
    /** Tolerates NaN in a damaged file: such values become null instead of failing the whole answer. */
    private static final Gson TREE = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    private McpJson() {
    }

    /** Time at a tick as people read it, {@code 1:02.5}, with hours from one hour on. */
    public static String time(int tick) {
        return ExportNames.clock(Math.max(0, tick), tick >= ExportNames.HOUR_TICKS);
    }

    /** {@code value} as a JSON tree, with numbers that are not finite replaced by null. */
    public static JsonElement tree(Object value) {
        return finite(TREE.toJsonTree(value));
    }

    private static JsonElement finite(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return Double.isNaN(d) || Double.isInfinite(d) ? JsonNull.INSTANCE : p;
            }
            return p;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : e.getAsJsonArray()) {
                out.add(finite(item));
            }
            return out;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
            out.add(m.getKey(), finite(m.getValue()));
        }
        return out;
    }

    // ------------------------------------------------------------------ state

    public static JsonObject state(McpState s) {
        JsonObject o = tree(s).getAsJsonObject();
        o.addProperty("ticksPerSecond", TICKS_PER_SECOND);
        JsonObject recording = o.getAsJsonObject("recording");
        if (s.recording.active) {
            recording.addProperty("time", time(s.recording.ticks));
        }
        JsonObject replay = o.getAsJsonObject("replay");
        if (s.replay.open) {
            replay.addProperty("time", time(s.replay.tick));
            replay.addProperty("duration", time(s.replay.durationTicks));
        }
        JsonObject export = o.getAsJsonObject("export");
        if (s.export.active) {
            export.addProperty("startTime", time(s.export.startTick));
            export.addProperty("endTime", time(s.export.endTick));
        }
        return o;
    }

    // ------------------------------------------------------------------ replays

    /** One saved replay of {@code list_replays}. */
    public static JsonObject replayEntry(String id, ReplayMeta meta, long sizeBytes, boolean hasProject, boolean open) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", meta.name == null || meta.name.isEmpty() ? id : meta.name);
        o.addProperty("singleplayer", meta.singleplayer);
        o.addProperty("server", text(meta.server));
        if (meta.startedAt > 0) {
            o.addProperty("recordedAt", Instant.ofEpochMilli(meta.startedAt).toString());
        }
        o.addProperty("durationTicks", meta.durationTicks);
        o.addProperty("duration", time(meta.durationTicks));
        o.addProperty("minecraft", text(meta.mc).isEmpty() ? text(meta.minecraftVersion) : meta.mc);
        o.addProperty("loader", text(meta.loader));
        o.addProperty("modVersion", text(meta.modVersion));
        o.addProperty("recordedBy", text(meta.recorderName));
        o.addProperty("recordingMarkers", meta.markers == null ? 0 : meta.markers.size());
        o.addProperty("mods", meta.mods == null ? 0 : meta.mods.size());
        o.addProperty("sizeBytes", sizeBytes);
        o.addProperty("hasProject", hasProject);
        o.addProperty("open", open);
        return o;
    }

    /** What a project or marker answer says about its replay. */
    public static JsonObject replayInfo(String id, ReplayMeta meta, boolean open, int tick, int durationTicks) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", meta == null || meta.name == null || meta.name.isEmpty() ? id : meta.name);
        o.addProperty("open", open);
        o.addProperty("durationTicks", durationTicks);
        o.addProperty("duration", time(durationTicks));
        if (open) {
            o.addProperty("playheadTick", tick);
            o.addProperty("playhead", time(tick));
        }
        return o;
    }

    // ------------------------------------------------------------------ project

    /**
     * The asked sections of a project. In a summary, keyframe lists (and actor actions) shrink to their count and
     * first and last ticks; {@code full} gives them as stored.
     */
    public static JsonObject project(JsonObject replay, ReplayMeta meta, ReplayProject p, List<String> sections, boolean full,
                                     int durationTicks) {
        JsonObject out = new JsonObject();
        out.add("replay", replay);
        out.addProperty("detail", full ? "full" : "summary");
        out.addProperty("ticksPerSecond", TICKS_PER_SECOND);
        for (String section : sections.isEmpty() ? SECTIONS : sections) {
            out.add(section, section(section, meta, p, full, durationTicks));
        }
        return out;
    }

    private static JsonElement section(String section, ReplayMeta meta, ReplayProject p, boolean full, int durationTicks) {
        switch (section) {
            case "overview":
                return overview(meta, p, durationTicks);
            case "cameras":
                return shaped(p.cameras, full);
            case "cuts": {
                JsonArray cuts = new JsonArray();
                for (ReplayProject.Cut cut : p.cuts) {
                    JsonObject c = tree(cut).getAsJsonObject();
                    ReplayProject.Camera camera = p.camera(cut.camera);
                    c.addProperty("cameraName", camera == null ? "" : camera.name);
                    c.addProperty("time", time(cut.tick));
                    cuts.add(c);
                }
                return cuts;
            }
            case "actors":
                return shaped(p.actors, full);
            case "actions": {
                JsonArray actions = new JsonArray();
                for (ReplayProject.ActorData actor : p.actors) {
                    for (ReplayProject.ActorAction action : actor.actions) {
                        JsonObject a = tree(action).getAsJsonObject();
                        a.addProperty("actorId", actor.id);
                        a.addProperty("actorName", actor.name);
                        a.addProperty("time", time(action.tick));
                        actions.add(a);
                    }
                }
                return actions;
            }
            case "audio": {
                JsonObject audio = new JsonObject();
                audio.addProperty("gameVolume", p.gameVolume);
                audio.addProperty("muteGame", p.muteGame);
                audio.addProperty("ducking", p.ducking);
                audio.addProperty("duckAmount", p.duckAmount);
                audio.addProperty("voices", p.voices);
                audio.add("soundGains", tree(p.soundGains));
                JsonArray tracks = new JsonArray();
                for (AudioTrack track : p.audioTracks) {
                    JsonObject t = tree(track).getAsJsonObject();
                    JsonArray clips = new JsonArray();
                    for (AudioClip clip : track.clips) {
                        JsonObject c = tree(clip).getAsJsonObject();
                        c.addProperty("startTime", time(clip.start));
                        clips.add(c);
                    }
                    t.add("clips", clips);
                    tracks.add(t);
                }
                audio.add("tracks", tracks);
                return audio;
            }
            case "effects": {
                JsonObject fx = new JsonObject();
                fx.addProperty("effectsEnabled", p.effectsEnabled);
                fx.addProperty("fadeIn", p.fadeIn);
                fx.addProperty("fadeOut", p.fadeOut);
                fx.addProperty("fadeWhite", p.fadeWhite);
                fx.add("effects", shaped(p.effects, full));
                return fx;
            }
            case "overlays":
                return shaped(p.overlays, full);
            case "world":
                return shaped(p.world, full);
            case "speed":
                return shaped(p.speed, full);
            case "entities": {
                JsonObject entities = new JsonObject();
                entities.add("hiddenEntityTypes", tree(p.hiddenEntityTypes));
                entities.add("entityEdits", shaped(p.entityEdits, full));
                return entities;
            }
            default:
                return JsonNull.INSTANCE;
        }
    }

    private static JsonObject overview(ReplayMeta meta, ReplayProject p, int durationTicks) {
        JsonObject o = new JsonObject();
        o.addProperty("cameras", p.cameras.size());
        o.addProperty("cuts", p.cuts.size());
        o.addProperty("actors", p.actors.size());
        int actions = 0;
        for (ReplayProject.ActorData actor : p.actors) {
            actions += actor.actions.size();
        }
        o.addProperty("actions", actions);
        int clips = 0;
        for (AudioTrack track : p.audioTracks) {
            clips += track.clips.size();
        }
        o.addProperty("audioTracks", p.audioTracks.size());
        o.addProperty("audioClips", clips);
        o.addProperty("effects", p.effects.size());
        o.addProperty("overlays", p.overlays.size());
        o.addProperty("markers", p.markers.size());
        o.addProperty("entityEdits", p.entityEdits.size());
        o.addProperty("hiddenEntityTypes", p.hiddenEntityTypes.size());
        ReplayProject.Camera selected = p.camera(p.selectedCamera);
        o.addProperty("selectedCamera", selected == null ? "" : selected.id);
        o.addProperty("selectedCameraName", selected == null ? "" : selected.name);
        int[] range = p.exportRange(durationTicks);
        if (range != null && range.length == 2) {
            o.addProperty("exportStartTick", range[0]);
            o.addProperty("exportEndTick", range[1]);
            o.addProperty("exportRange", time(range[0]) + " - " + time(range[1]));
        }
        o.addProperty("showPath", p.showPath);
        o.addProperty("showMarkers", p.showMarkers);
        o.addProperty("effectsEnabled", p.effectsEnabled);
        if (meta != null && !p.recordingMarkersImported && meta.markers != null && !meta.markers.isEmpty()) {
            // Placed while recording: they become markers the first time the replay is opened in the game.
            JsonArray recorded = new JsonArray();
            for (ReplayMeta.Marker m : meta.markers) {
                if (m != null) {
                    JsonObject r = new JsonObject();
                    r.addProperty("tick", m.tick);
                    r.addProperty("time", time(m.tick));
                    r.addProperty("name", text(m.name));
                    recorded.add(r);
                }
            }
            o.add("recordingMarkersNotImported", recorded);
        }
        return o;
    }

    /** The tree of {@code value}, shortened for a summary. */
    private static JsonElement shaped(Object value, boolean full) {
        JsonElement tree = tree(value);
        return full ? tree : summarize(tree);
    }

    /** Lists of keyframes become {@code {count, firstTick, lastTick}}, in every object of the tree. */
    static JsonElement summarize(JsonElement e) {
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : e.getAsJsonArray()) {
                out.add(summarize(item));
            }
            return out;
        }
        if (!e.isJsonObject()) {
            return e;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
            String key = m.getKey();
            JsonElement v = m.getValue();
            if (isKeyList(key) && v.isJsonArray()) {
                out.add(key, span(v.getAsJsonArray()));
            } else if (isKeyList(key) && v.isJsonObject()) {
                // Keys per parameter (effects, overlays): each list shrinks.
                JsonObject perParam = new JsonObject();
                for (Map.Entry<String, JsonElement> param : v.getAsJsonObject().entrySet()) {
                    perParam.add(param.getKey(), param.getValue().isJsonArray() ? span(param.getValue().getAsJsonArray()) : param.getValue());
                }
                out.add(key, perParam);
            } else {
                out.add(key, summarize(v));
            }
        }
        return out;
    }

    private static boolean isKeyList(String key) {
        return key.equals("keyframes") || key.equals("keys") || key.equals("offsets") || key.equals("actions")
                || key.equals("skinChanges") || key.endsWith("Keys");
    }

    private static JsonObject span(JsonArray list) {
        JsonObject o = new JsonObject();
        o.addProperty("count", list.size());
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (JsonElement item : list) {
            if (item.isJsonObject() && item.getAsJsonObject().has("tick") && item.getAsJsonObject().get("tick").isJsonPrimitive()) {
                int tick = item.getAsJsonObject().get("tick").getAsInt();
                first = Math.min(first, tick);
                last = Math.max(last, tick);
            }
        }
        if (first <= last) {
            o.addProperty("firstTick", first);
            o.addProperty("lastTick", last);
        }
        return o;
    }

    // ------------------------------------------------------------------ markers

    /** A marker with its note, its times and what it is pinned to. */
    public static JsonObject marker(Marker m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id);
        o.addProperty("name", text(m.name));
        o.addProperty("type", m.type);
        o.addProperty("tick", m.tick);
        o.addProperty("time", time(m.tick));
        if (m.isRange()) {
            o.addProperty("endTick", m.end);
            o.addProperty("endTime", time(m.end));
            o.addProperty("lengthTicks", m.length());
            o.addProperty("length", ExportNames.length(m.tick, m.end));
        }
        o.addProperty("note", text(m.note));
        o.addProperty("color", String.format(Locale.ROOT, "#%06X", m.color & 0xFFFFFF));
        String colorName = colorName(m.color);
        if (colorName != null) {
            o.addProperty("colorName", colorName);
        }
        if (m.isPoint()) {
            o.addProperty("x", m.x);
            o.addProperty("y", m.y);
            o.addProperty("z", m.z);
            o.addProperty("dimension", text(m.dimension));
        }
        if (m.isEntity()) {
            o.addProperty("target", text(m.target));
            o.addProperty("targetName", text(m.targetName));
            o.addProperty("targetIsRecordingPlayer", Marker.RECORDER.equals(m.target));
        }
        return finite(o).getAsJsonObject();
    }

    private static String colorName(int color) {
        for (int i = 0; i < Marker.COLORS.length && i < Marker.COLOR_NAMES.length; i++) {
            if (Marker.COLORS[i] == (color & 0xFFFFFF)) {
                return Marker.COLOR_NAMES[i];
            }
        }
        return null;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
