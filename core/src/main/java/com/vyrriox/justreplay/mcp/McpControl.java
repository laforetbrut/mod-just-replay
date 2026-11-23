package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayRename;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What the control tools need from the game, given by the game side: the same calls the editor, the keys and the
 * replay list make. Every method runs on the game thread unless it says "any thread"; the replay itself, its project
 * and its entities are read through {@link McpObserver}.
 *
 * @author vyrriox
 */
public interface McpControl {
    // ------------------------------------------------------------------ replays

    /**
     * Opens a saved replay for watching and editing, as the replay list does; a replay already open is left first.
     * Returns null once it is on its way (the tool then waits for it), else why it cannot open now.
     */
    String openReplay(Path dir);

    /** What opening a replay is waiting on now. */
    Opening opening();

    /** Where opening a replay stands. */
    enum Opening {
        /** Nothing is being opened. */
        NONE,
        /** Waiting for the replay open before it to close. */
        PENDING,
        /** The replay is being read and its world built. */
        LOADING,
        /** The replay was recorded with other mods: the game asks the player whether to open it anyway. */
        MODS_WARNING
    }

    /** Leaves the open replay for the title screen, its project saved: null when it is leaving, else why not. */
    String closeReplay();

    /** True once no world is loaded and a menu is shown. */
    boolean atMenu();

    // ------------------------------------------------------------------ playback

    void setPaused(boolean paused);

    /** Speeds playback can take, slowest first (1 is real time). Any thread. */
    float[] speeds();

    /** Sets the playback speed, one of {@link #speeds()}. */
    void setSpeed(float speed);

    /** Plays from the play head and pauses on {@code tick}, the world ticking on the way. */
    void playTo(int tick);

    // ------------------------------------------------------------------ view

    /**
     * Puts the free camera at {@code camera} (eye position, angles, roll and field of view), leaving the camera path
     * and the entity it viewed through. Roll and field of view hold until the replay closes or the camera path or an
     * entity takes over the view.
     */
    void placeCamera(McpCamera camera);

    /** Views through an entity of the replay found by {@code key} (see {@link McpEntity#matches}): its name, or null when there is none. */
    String spectate(String key);

    /** Back to the free camera, at the eyes of the entity that was viewed. */
    void stopSpectating();

    /** Lets the project's camera path drive the view, or gives the view back to the free camera. */
    void setCameraPath(boolean active);

    // ------------------------------------------------------------------ editing

    /**
     * After the tools changed the project of the open replay: saves it as the editor does (one step of the undo history,
     * {@code project.json} written), rebuilds the actors whose look changed, forgets the removed ones and reopens the
     * editor on the project when it is shown, as undo does.
     */
    void projectChanged(Collection<ReplayProject.ActorData> rebuilt, Collection<ReplayProject.ActorData> removed);

    /** Writes the project of the open replay as the editor does (one step of history when it changed), and nothing else. */
    void saveProject();

    /**
     * Where the editor's actor keyframe button puts an actor: under the camera, on the ground within 4 blocks below
     * it (else at the camera's height), facing the camera's way, as {@code {x, y, z, yaw}}.
     */
    double[] underCamera();

    /**
     * The parameter the editor gives a new action of {@code type} on {@code actor}: the spot under the camera for
     * WALK_TO and RUN_TO, the first animation of a model actor for ANIMATION, the type's own default otherwise.
     */
    String defaultActionParam(ReplayProject.ActorData actor, String type);

    /** The spot of the ground the view looks at (the middle of the window) as {@code {x, y, z}}, or null when it looks at no block in reach. */
    double[] lookSpot();

    boolean canUndo();

    boolean canRedo();

    /** Undoes the last change of the project (the editor's history, shared with the player); false when there is none. */
    boolean undo();

    /** Applies again the last change undone; false when there is none. */
    boolean redo();

    /** Name of the player using the game: the account skin a new player actor wears. */
    String playerName();

    /**
     * Files the editor can use, as the project stores them: {@code skins} (PNG), {@code models} (Blockbench),
     * {@code audio}, {@code images} (overlays) and {@code luts} (LUT filters).
     */
    List<String> files(String kind);

    /** Animations of a Blockbench model of the models folder, empty when it has none or cannot be read. */
    List<String> modelAnimations(String file);

    /**
     * Decodes an audio file of the audio folder, off the game thread: completes with its length in ticks, or
     * exceptionally with why it cannot be played. Any thread.
     */
    CompletableFuture<Double> audioLength(String file);

    /** Whether {@code id} is in a registry of the game: {@code item} or {@code entity_type}. */
    boolean known(String registry, String id);

    /** Body parts a player can hide, by name, with the bit each one has in the masks of the project. Any thread. */
    Map<String, Integer> bodyParts();

    /** Kinds of game sounds the project gives a volume to (footsteps, players, blocks...). Any thread. */
    List<String> soundKinds();

    // ------------------------------------------------------------------ recording

    /** Starts recording the world the player is in, for {@code ticks} (0: until stopped). Null when it started, else why not. */
    String startRecording(int ticks);

    /**
     * Stops the recording and writes it: its folder, or null when none ran. With {@code offerName} the naming window
     * comes up as when the player presses the key.
     */
    Stopped stopRecording(boolean offerName);

    /** A recording that stopped; {@code incomplete} when its files could not all be written (disk error, timeout). */
    record Stopped(Path dir, boolean incomplete) {
    }

    /**
     * Saves the last minutes kept in memory as a replay, as the key does: completes with its folder. Null when they
     * are not kept (the setting is off or no world is loaded). With {@code offerName} the naming window comes up.
     */
    CompletableFuture<Path> saveInstantReplay(boolean offerName);

    /** Renames a saved replay the way the naming window does, one rename at a time, never over another replay. Any thread. */
    CompletableFuture<ReplayRename.Result> rename(Path dir, String name);

    /** True when the replay of this folder is open, being written or renamed: it must not be renamed or deleted now. */
    boolean busy(Path dir);

    // ------------------------------------------------------------------ export

    /** Starts a video export of the open replay: null when it started, else why not. */
    String startExport(ExportRequest request);

    /** The last export started (by anyone), running or finished, or null. */
    ExportInfo lastExport();

    /** Stops the running export; its unfinished file is removed. False when none ran. */
    boolean cancelExport();

    /** Folder of the exported videos. Any thread. */
    Path exportRoot();

    /**
     * One export.
     *
     * @param name        file name before the range and extension, made safe
     * @param fps         frames per second, null for the settings
     * @param crf         x264 quality factor, null for the settings
     * @param resolution  name of a size of the export settings ({@code WINDOW}, {@code HD_720}...), null for the settings
     * @param width       exact width in pixels with {@code height}, 0 for the resolution
     * @param pngSequence a folder of PNG frames (and a WAV) instead of an MP4
     * @param rangeInName the exported range ends the file name, null for the settings
     */
    record ExportRequest(int from, int to, String name, Integer fps, Integer crf, String resolution, int width, int height,
                         boolean pngSequence, Boolean rangeInName) {
    }

    /**
     * An export, as the game reports it.
     *
     * @param file      file (or frame folder) name
     * @param done      it ended, well or not
     * @param success   it ended with its file written
     * @param cancelled it was cancelled
     * @param progress  0 to 1
     */
    record ExportInfo(String file, Path output, int from, int to, boolean active, boolean done, boolean success, boolean cancelled,
                      long frames, long totalFrames, float progress, long elapsedMillis, boolean preparing, boolean finishing,
                      double videoSeconds, int fps) {
    }
}
