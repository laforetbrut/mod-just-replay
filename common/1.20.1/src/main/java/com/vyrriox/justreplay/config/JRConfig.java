package com.vyrriox.justreplay.config;

import com.vyrriox.justreplay.compat.CompatSwitches;
import com.vyrriox.justreplay.compat.CompatTargets;
import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.mcp.McpConfig;
import com.vyrriox.justreplay.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client configuration of Just Replay.
 *
 * @author vyrriox
 */
public final class JRConfig {
    public static final ConfigFile SPEC;
    public static final ConfigFile.BoolValue AUTO_RECORD_MULTIPLAYER;
    public static final ConfigFile.BoolValue AUTO_RECORD_SINGLEPLAYER;
    public static final ConfigFile.BoolValue SHOW_RECORDING_INDICATOR;
    public static final ConfigFile.BoolValue CAPTURE_EXTRA_CHUNKS;
    public static final ConfigFile.BoolValue CAPTURE_VOICE_CHAT;
    public static final ConfigFile.IntValue EXTRA_CHUNK_RADIUS;
    public static final ConfigFile.IntValue CHECKPOINT_MINUTES;
    public static final ConfigFile.BoolValue REPLAY_BUFFER;
    public static final ConfigFile.IntValue BUFFER_MINUTES;
    public static final ConfigFile.IntValue NEXT_MINUTES;
    public static final ConfigFile.BoolValue NAME_ON_SAVE;
    public static final ConfigFile.IntValue SEEK_STEP_SECONDS;
    public static final ConfigFile.BoolValue CLEAN_HUD;
    public static final ConfigFile.BoolValue HIDE_PLAYER_ARMOR;
    public static final ConfigFile.IntValue PLAYBACK_VIEW_DISTANCE;
    public static final ConfigFile.BoolValue WARN_MOD_CHANGES;
    public static final ConfigFile.EnumValue<ServerResourcePacks> SERVER_RESOURCE_PACKS;
    public static final ConfigFile.BoolValue DISTANT_HORIZONS_LODS;
    public static final ConfigFile.IntValue DISTANT_HORIZONS_MAX_COPY_MB;
    public static final ConfigFile.StringValue FFMPEG_PATH;
    public static final ConfigFile.IntValue EXPORT_FPS;
    public static final ConfigFile.IntValue EXPORT_CRF;
    public static final ConfigFile.IntValue EXPORT_WARMUP_FRAMES;
    public static final ConfigFile.EnumValue<ExportResolution> EXPORT_RESOLUTION;
    public static final ConfigFile.BoolValue EXPORT_RANGE_IN_NAME;
    public static final ConfigFile.EnumValue<EditorMode> EDITOR_MODE;
    /** The local MCP server, off until switched on. */
    public static final McpConfig MCP;
    /** One switch per integration, by mod id (see {@link CompatSwitches}, which reads them before the game starts). */
    public static final Map<String, ConfigFile.BoolValue> COMPAT;

    /** How much of the replay editor is shown. */
    public enum EditorMode {
        /** The essentials with big controls and plain words. */
        SIMPLE,
        /** Every tool: several cameras, cuts, blends, keyframe editing, models, voices. */
        ADVANCED
    }

    /** What playback does with the resource packs the recorded server sent. */
    public enum ServerResourcePacks {
        /** Applies the packs found in the game's cache of server packs, the ones downloaded while playing there. */
        CACHED,
        /** As CACHED, and downloads the missing ones from the address the server gave. */
        DOWNLOAD,
        /** Never applies them: the replay looks like the recording player's own packs. */
        OFF
    }

    static {
        Path dir = Platform.get().configDir();
        Path file = dir.resolve(CompatSwitches.FILE_NAME);
        SPEC = new ConfigFile(file, "Just Replay client settings.");
        ConfigFile.Section recording = SPEC.section("recording", "Recording.");
        AUTO_RECORD_MULTIPLAYER = recording.bool("autoRecordMultiplayer", false,
                "Start recording automatically when joining a server.");
        AUTO_RECORD_SINGLEPLAYER = recording.bool("autoRecordSingleplayer", false,
                "Start recording automatically when opening a singleplayer world.");
        SHOW_RECORDING_INDICATOR = recording.bool("showRecordingIndicator", true,
                "Show the REC indicator in the top left corner while recording. Off: the recording goes on with nothing on screen,"
                        + " so screen captures (OBS) stay clean.");
        CAPTURE_EXTRA_CHUNKS = recording.bool("captureExtraChunks", true,
                "Also record the chunks the client has beyond the server view distance (cached chunks of mods" + "\n"
                        + "such as Bobby), a few per tick, so replays show the whole render distance.");
        CAPTURE_VOICE_CHAT = recording.bool("captureVoiceChat", true,
                "Record Simple Voice Chat audio (other players and your microphone) when that mod is installed.");
        EXTRA_CHUNK_RADIUS = recording.integer("extraChunkRadius", 16, 2, 48,
                "Largest radius (in chunks around the player) of the cached chunks and checkpoints recorded beyond the" + "\n"
                        + "server view distance. Each chunk is written once per recording; a larger radius means more data on disk.");
        CHECKPOINT_MINUTES = recording.integer("checkpointMinutes", 5, 1, 30,
                "Minutes between two checkpoints of a recording (snapshots that make seeking fast)." + "\n"
                        + "Fewer checkpoints means fewer bytes written; recordings shorter than this have none.");
        REPLAY_BUFFER = recording.bool("replayBuffer", false,
                "Instant replay: always keep the last minutes of play in memory (compressed), from joining a world or server," + "\n"
                        + "so the 'Save last minutes' key can save them as a replay after the fact.");
        BUFFER_MINUTES = recording.integer("bufferMinutes", 5, 1, 120,
                "Minutes kept by the instant replay buffer (1 to 120). Saved clips start at the last checkpoint before that window," + "\n"
                        + "so they can be up to a minute longer (a fifth longer for long windows)." + "\n"
                        + "WARNING: the buffer lives in RAM. Expect roughly 10 to 25 MB per 10 minutes on a busy server, more with" + "\n"
                        + "cached chunks: about 150 to 300 MB for 2 hours. Lower it if your game is short on memory.");
        NEXT_MINUTES = recording.integer("nextMinutes", 5, 1, 240,
                "Length of a recording started with the 'Record next minutes' key; it stops by itself.");
        NAME_ON_SAVE = recording.bool("nameOnSave", true,
                "Ask for a name when a recording stops or the last minutes are saved. The replay is saved under" + "\n"
                        + "the date and time first; Esc or Skip keeps that name.");
        ConfigFile.Section playback = SPEC.section("playback", "Watching replays.");
        SEEK_STEP_SECONDS = playback.integer("seekStepSeconds", 5, 1, 600,
                "Seconds skipped by the seek keys.");
        CLEAN_HUD = playback.bool("cleanHud", true,
                "Hide the hotbar, health, crosshair and every HUD added by other mods while watching a replay." + "\n"
                        + "Chat, titles, boss bars, scoreboard and the tab list stay visible since they are part of the recording.");
        HIDE_PLAYER_ARMOR = playback.bool("hidePlayerArmor", false,
                "Hide the armor of every player (helmet, chestplate, leggings, boots, elytra) while watching a replay" + "\n"
                        + "and in exported videos. The editor can also hide it on one player or one actor.");
        PLAYBACK_VIEW_DISTANCE = playback.integer("viewDistance", 32, 8, 64,
                "Chunks kept around the camera while watching a replay. Chunks the recording server unloaded" + "\n"
                        + "stay visible up to this distance instead of disappearing.");
        WARN_MOD_CHANGES = playback.bool("warnModChanges", true,
                "Before opening a replay, list the mods that are missing, were added or changed version since it was" + "\n"
                        + "recorded, with a choice to open it anyway. A replay without a mod list opens directly.");
        SERVER_RESOURCE_PACKS = playback.enumeration("serverResourcePacks", ServerResourcePacks.CACHED,
                "Resource pack the recorded server sent (textures, models, sounds). CACHED applies the ones found in the" + "\n"
                        + "game's cache of server packs, which holds the packs downloaded while playing there; DOWNLOAD also" + "\n"
                        + "downloads the missing ones from the server's address; OFF never applies them. They are removed when the replay closes.");
        DISTANT_HORIZONS_LODS = playback.bool("distantHorizonsLods", true,
                "With Distant Horizons installed, show the distant terrain (LODs) of the recorded world in replays and" + "\n"
                        + "exported videos. The recorded world's LOD database is copied to a temporary folder when the replay opens and" + "\n"
                        + "read from there, so the original is never opened or changed; the copy is deleted when the replay closes." + "\n"
                        + "Needs a recording made with this option's support (older recordings play without LODs).");
        DISTANT_HORIZONS_MAX_COPY_MB = playback.integer("distantHorizonsMaxCopyMb", 4096, 16, 1048576,
                "Largest Distant Horizons database (in MB, per dimension) copied for a replay. A larger one is" + "\n"
                        + "skipped and that dimension plays without LODs, to spare the drive.");
        ConfigFile.Section export = SPEC.section("export", "Video export.");
        FFMPEG_PATH = export.string("ffmpegPath", "ffmpeg",
                "Path to the ffmpeg executable, or just 'ffmpeg' when it is on the PATH.");
        EXPORT_FPS = export.integer("fps", 60, 10, 240,
                "Frames per second of exported videos.");
        EXPORT_CRF = export.integer("crf", 18, 0, 51,
                "x264 quality factor, lower is better (18 is visually lossless).");
        EXPORT_WARMUP_FRAMES = export.integer("warmupFrames", 90, 0, 2000,
                "Minimum frames rendered before capture starts; capture also waits for the chunks to be built.");
        EXPORT_RESOLUTION = export.enumeration("resolution", ExportResolution.WINDOW,
                "Size of exported videos. WINDOW keeps the window size; the others render at that size" + "\n"
                        + "whatever the window, or are scaled by FFmpeg when the graphics card cannot render that large.");
        EXPORT_RANGE_IN_NAME = export.bool("rangeInFileName", true,
                "End the file name of an export with the part of the replay it holds (Boss_fight_00m10s-00m20s.mp4, with" + "\n"
                        + "hours for replays of an hour or more). Off keeps the old names (Boss_fight.mp4).");
        ConfigFile.Section editor = SPEC.section("editor", "Replay editor.");
        EDITOR_MODE = editor.enumeration("mode", EditorMode.SIMPLE,
                "SIMPLE shows the essentials of the replay editor with big controls; ADVANCED shows every tool." + "\n"
                        + "Switching keeps all the work done in either mode.");
        MCP = McpConfig.define(SPEC);
        ConfigFile.Section compat = SPEC.section(CompatSwitches.SECTION,
                "Integrations with other mods, one switch per mod id. An integration that fails turns itself off until the" + "\n"
                        + "next start and says so in the log; set it to false here to keep it off. Changes apply at the next start.");
        Map<String, ConfigFile.BoolValue> switches = new LinkedHashMap<>();
        for (Map.Entry<String, String> mod : CompatTargets.NAMES.entrySet()) {
            switches.put(mod.getKey(), compat.bool(mod.getKey(), true, mod.getValue() + " integration (applies at the next start)."));
        }
        COMPAT = Collections.unmodifiableMap(switches);
        boolean firstRun = !Files.exists(file);
        SPEC.load();
        Path legacy = dir.resolve("justreplay-client.toml");
        if (firstRun && Files.isRegularFile(legacy)) {
            LegacyTomlImport.apply(SPEC, legacy);
            SPEC.save();
        }
    }

    private JRConfig() {
    }
}
