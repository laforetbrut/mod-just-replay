package com.vyrriox.justreplay.mcp;

/**
 * What the mod is doing, as plain values the game side fills on the game thread for the {@code get_state} tool.
 * Ticks are 1/20 s.
 *
 * @author vyrriox
 */
public final class McpState {
    public String minecraft = "";
    public String loader = "";
    public String modVersion = "";
    /** The open screen: {@code none} in the world, else a short name such as {@code title}, {@code pause} or {@code editor}. */
    public String screen = "none";
    public final World world = new World();
    public final Recording recording = new Recording();
    public final InstantReplay instantReplay = new InstantReplay();
    public final Replay replay = new Replay();
    public final Editor editor = new Editor();
    public final Export export = new Export();

    public static final class World {
        public boolean loaded;
        /** True in the world of a replay being watched. */
        public boolean replayWorld;
        public boolean singleplayer;
        /** Address of the server, empty in singleplayer. */
        public String server = "";
        public String dimension = "";
    }

    public static final class Recording {
        public boolean active;
        /** Id (folder name) of the replay being written. */
        public String replayId = "";
        public int ticks;
        /** A timed recording stops by itself at this tick, -1 otherwise. */
        public int stopsAtTick = -1;
    }

    public static final class InstantReplay {
        /** The setting that keeps the last minutes in memory. */
        public boolean enabled;
        /** Whether the last minutes are being kept right now. */
        public boolean capturing;
        public int minutes;
    }

    public static final class Replay {
        public boolean open;
        /** A replay is being loaded. */
        public boolean opening;
        public String replayId = "";
        public String name = "";
        public int tick;
        public int durationTicks;
        public boolean paused;
        public float speed = 1.0F;
        /** The camera follows the edited camera path. */
        public boolean cameraPath;
        /** Name of the entity the camera spectates, empty when free. */
        public String spectating = "";
    }

    public static final class Editor {
        public boolean open;
        /** {@code SIMPLE} or {@code ADVANCED}. */
        public String mode = "";
    }

    public static final class Export {
        public boolean active;
        /** File name of the video being written. */
        public String file = "";
        public int startTick;
        public int endTick;
        /** 0 to 1. */
        public float progress;
        public long frames;
        public long totalFrames;
        public boolean preparing;
        public boolean finishing;
    }
}
