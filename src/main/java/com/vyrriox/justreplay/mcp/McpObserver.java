package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayMeta;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What the observation tools need from the game, given by the game side. Every method runs on the game thread only,
 * and what it returns is plain data read there.
 *
 * @author vyrriox
 */
public interface McpObserver {
    /** The replay open in the game, or null. */
    Replay replay();

    /**
     * Moves the open replay to {@code tick} at once: the recorded packets up to it are handled and the actors posed
     * there, nothing is drawn meanwhile. Going back rebuilds the world from the nearest checkpoint. Returns the tick
     * reached.
     */
    int seek(int tick);

    /** The entities of the level shown, at the play head; the replay camera itself is left out. */
    List<McpEntity> entities();

    /** One entity of the level shown by its ref, UUID, network id or actor id (see {@link McpEntity#matches}), or null. */
    McpEntity entity(String key);

    /** Dimension, time and weather of the level shown. */
    World world();

    /** The block at a position of the level shown. */
    Block block(int x, int y, int z);

    /** The players of the tab list at the play head. */
    List<Player> tabList();

    /** The camera the window shows now. */
    McpCamera currentCamera();

    /** The camera of the project at a tick (cut track or selected camera), or null when it has no keyframes. */
    McpCamera projectCamera(int tick);

    /** Width and height of the window in pixels. */
    int[] windowSize();

    /**
     * Starts drawing a frame. The future completes on the game thread with the picture, or exceptionally with an
     * {@link McpArgs.Invalid} the caller can read; cancelling it stops the drawing and puts everything back.
     *
     * @throws McpArgs.Invalid when no frame can be drawn now (an export is running, for instance)
     */
    CompletableFuture<Frame> render(FrameRequest request) throws McpArgs.Invalid;

    /**
     * The open replay.
     *
     * @param id    its folder name, as {@code list_replays} gives it
     * @param tick  where the play head is
     * @param speed     playback speed (1 is real time)
     * @param exporting a video export or a frame is being drawn: the play head must not move
     */
    record Replay(String id, ReplayMeta meta, ReplayProject project, int tick, int durationTicks, boolean paused, double speed,
                  boolean exporting) {
    }

    /**
     * What a frame shows and how big it is.
     *
     * @param tick   tick shown
     * @param camera camera to draw from; null draws as the video export does (the project's camera path) without the
     *               editor, and from the view the window shows with it
     * @param ui     draws the window as the player sees it, editor and HUD included, instead of a video frame
     */
    record FrameRequest(int tick, McpCamera camera, int width, int height, boolean ui) {
    }

    /**
     * A drawn frame: RGBA bytes, top row first, as the video export reads them.
     *
     * @param camera the camera it was drawn from
     */
    record Frame(byte[] rgba, int width, int height, int tick, McpCamera camera) {
    }

    /** The level shown. */
    final class World {
        public String dimension = "";
        /** Time of day the level shows (24000 per day), the project's world settings applied. */
        public long dayTime;
        /** Time of day the recording holds. */
        public long recordedDayTime;
        public long gameTime;
        public boolean raining;
        public boolean thundering;
        public float rainLevel;
        public float thunderLevel;
        public String difficulty = "";
        public int entities;
        public int loadedChunks;
        public int players;
    }

    /** One block of the level shown. */
    final class Block {
        public boolean loaded;
        /** Registry id, such as {@code minecraft:oak_log}. */
        public String id = "";
        /** Block state properties, such as {@code axis=y}. */
        public Map<String, String> properties = Map.of();
        public String fluid = "";
        public String blockEntity = "";
        public String biome = "";
        public int blockLight = -1;
        public int skyLight = -1;
        public boolean air;
    }

    /** One entry of the tab list. */
    record Player(String name, String uuid, String gameMode, int latency) {
    }
}
