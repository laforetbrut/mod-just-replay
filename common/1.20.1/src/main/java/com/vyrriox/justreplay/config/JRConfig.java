package com.vyrriox.justreplay.config;

import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;

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
    public static final ConfigFile.IntValue SEEK_STEP_SECONDS;
    public static final ConfigFile.BoolValue CLEAN_HUD;
    public static final ConfigFile.IntValue PLAYBACK_VIEW_DISTANCE;
    public static final ConfigFile.BoolValue DISTANT_HORIZONS_LODS;
    public static final ConfigFile.IntValue DISTANT_HORIZONS_MAX_COPY_MB;
    public static final ConfigFile.StringValue FFMPEG_PATH;
    public static final ConfigFile.IntValue EXPORT_FPS;
    public static final ConfigFile.IntValue EXPORT_CRF;
    public static final ConfigFile.IntValue EXPORT_WARMUP_FRAMES;
    public static final ConfigFile.EnumValue<ExportResolution> EXPORT_RESOLUTION;
    public static final ConfigFile.EnumValue<EditorMode> EDITOR_MODE;

    /** How much of the replay editor is shown. */
    public enum EditorMode {
        /** The essentials with big controls and plain words. */
        SIMPLE,
        /** Every tool: several cameras, cuts, blends, keyframe editing, models, voices. */
        ADVANCED
    }

    static {
        Path dir = Platform.get().configDir();
        Path file = dir.resolve("justreplay-client.json");
        SPEC = new ConfigFile(file, "Just Replay client settings.");
        ConfigFile.Section recording = SPEC.section("recording", "Recording.");
        AUTO_RECORD_MULTIPLAYER = recording.bool("autoRecordMultiplayer", false,
                "Start recording automatically when joining a server.");
        AUTO_RECORD_SINGLEPLAYER = recording.bool("autoRecordSingleplayer", false,
                "Start recording automatically when opening a singleplayer world.");
        SHOW_RECORDING_INDICATOR = recording.bool("showRecordingIndicator", true,
                "Show the REC indicator while recording.");
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
        ConfigFile.Section playback = SPEC.section("playback", "Watching replays.");
        SEEK_STEP_SECONDS = playback.integer("seekStepSeconds", 5, 1, 600,
                "Seconds skipped by the seek keys.");
        CLEAN_HUD = playback.bool("cleanHud", true,
                "Hide the hotbar, health, crosshair and every HUD added by other mods while watching a replay." + "\n"
                        + "Chat, titles, boss bars, scoreboard and the tab list stay visible since they are part of the recording.");
        PLAYBACK_VIEW_DISTANCE = playback.integer("viewDistance", 32, 8, 64,
                "Chunks kept around the camera while watching a replay. Chunks the recording server unloaded" + "\n"
                        + "stay visible up to this distance instead of disappearing.");
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
        ConfigFile.Section editor = SPEC.section("editor", "Replay editor.");
        EDITOR_MODE = editor.enumeration("mode", EditorMode.SIMPLE,
                "SIMPLE shows the essentials of the replay editor with big controls; ADVANCED shows every tool." + "\n"
                        + "Switching keeps all the work done in either mode.");
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
