package com.vyrriox.justreplay.config;

import com.vyrriox.justreplay.export.ExportResolution;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration of Just Replay.
 *
 * @author vyrriox
 */
public final class JRConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_RECORD_MULTIPLAYER;
    public static final ModConfigSpec.BooleanValue AUTO_RECORD_SINGLEPLAYER;
    public static final ModConfigSpec.BooleanValue SHOW_RECORDING_INDICATOR;
    public static final ModConfigSpec.BooleanValue CAPTURE_EXTRA_CHUNKS;
    public static final ModConfigSpec.BooleanValue CAPTURE_VOICE_CHAT;
    public static final ModConfigSpec.IntValue EXTRA_CHUNK_RADIUS;
    public static final ModConfigSpec.IntValue CHECKPOINT_MINUTES;
    public static final ModConfigSpec.BooleanValue REPLAY_BUFFER;
    public static final ModConfigSpec.IntValue BUFFER_MINUTES;
    public static final ModConfigSpec.IntValue NEXT_MINUTES;
    public static final ModConfigSpec.IntValue SEEK_STEP_SECONDS;
    public static final ModConfigSpec.BooleanValue CLEAN_HUD;
    public static final ModConfigSpec.IntValue PLAYBACK_VIEW_DISTANCE;
    public static final ModConfigSpec.ConfigValue<String> FFMPEG_PATH;
    public static final ModConfigSpec.IntValue EXPORT_FPS;
    public static final ModConfigSpec.IntValue EXPORT_CRF;
    public static final ModConfigSpec.IntValue EXPORT_WARMUP_FRAMES;
    public static final ModConfigSpec.EnumValue<ExportResolution> EXPORT_RESOLUTION;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("recording");
        AUTO_RECORD_MULTIPLAYER = b.comment("Start recording automatically when joining a server.")
                .define("autoRecordMultiplayer", false);
        AUTO_RECORD_SINGLEPLAYER = b.comment("Start recording automatically when opening a singleplayer world.")
                .define("autoRecordSingleplayer", false);
        SHOW_RECORDING_INDICATOR = b.comment("Show the REC indicator while recording.")
                .define("showRecordingIndicator", true);
        CAPTURE_EXTRA_CHUNKS = b.comment("Also record the chunks the client has beyond the server view distance (cached chunks of mods",
                        "such as Bobby), a few per tick, so replays show the whole render distance.")
                .define("captureExtraChunks", true);
        CAPTURE_VOICE_CHAT = b.comment("Record Simple Voice Chat audio (other players and your microphone) when that mod is installed.")
                .define("captureVoiceChat", true);
        EXTRA_CHUNK_RADIUS = b.comment("Largest radius (in chunks around the player) of the cached chunks and checkpoints recorded beyond the",
                        "server view distance. Each chunk is written once per recording; a larger radius means more data on disk.")
                .defineInRange("extraChunkRadius", 16, 2, 48);
        CHECKPOINT_MINUTES = b.comment("Minutes between two checkpoints of a recording (snapshots that make seeking fast).",
                        "Fewer checkpoints means fewer bytes written; recordings shorter than this have none.")
                .defineInRange("checkpointMinutes", 5, 1, 30);
        REPLAY_BUFFER = b.comment("Instant replay: always keep the last minutes of play in memory (compressed), from joining a world or server,",
                        "so the 'Save last minutes' key can save them as a replay after the fact.")
                .define("replayBuffer", false);
        BUFFER_MINUTES = b.comment("Minutes kept by the instant replay buffer. Saved clips start at the last checkpoint before that window,",
                        "so they can be up to a minute longer (a fifth longer for long windows).")
                .defineInRange("bufferMinutes", 5, 1, 30);
        NEXT_MINUTES = b.comment("Length of a recording started with the 'Record next minutes' key; it stops by itself.")
                .defineInRange("nextMinutes", 5, 1, 240);
        b.pop();
        b.push("playback");
        SEEK_STEP_SECONDS = b.comment("Seconds skipped by the seek keys.")
                .defineInRange("seekStepSeconds", 5, 1, 600);
        CLEAN_HUD = b.comment("Hide the hotbar, health, crosshair and every HUD added by other mods while watching a replay.",
                        "Chat, titles, boss bars, scoreboard and the tab list stay visible since they are part of the recording.")
                .define("cleanHud", true);
        PLAYBACK_VIEW_DISTANCE = b.comment("Chunks kept around the camera while watching a replay. Chunks the recording server unloaded",
                        "stay visible up to this distance instead of disappearing.")
                .defineInRange("viewDistance", 32, 8, 64);
        b.pop();
        b.push("export");
        FFMPEG_PATH = b.comment("Path to the ffmpeg executable, or just 'ffmpeg' when it is on the PATH.")
                .define("ffmpegPath", "ffmpeg");
        EXPORT_FPS = b.comment("Frames per second of exported videos.")
                .defineInRange("fps", 60, 10, 240);
        EXPORT_CRF = b.comment("x264 quality factor, lower is better (18 is visually lossless).")
                .defineInRange("crf", 18, 0, 51);
        EXPORT_WARMUP_FRAMES = b.comment("Minimum frames rendered before capture starts; capture also waits for the chunks to be built.")
                .defineInRange("warmupFrames", 90, 0, 2000);
        EXPORT_RESOLUTION = b.comment("Size of exported videos. WINDOW keeps the window size; the others render at that size",
                        "whatever the window, or are scaled by FFmpeg when the graphics card cannot render that large.")
                .defineEnum("resolution", ExportResolution.WINDOW);
        b.pop();
        SPEC = b.build();
    }

    private JRConfig() {
    }
}
