package com.vyrriox.justreplay.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata stored next to a replay stream in {@code meta.json}.
 *
 * @author vyrriox
 */
public final class ReplayMeta {
    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String name = "";
    public String server = "";
    public boolean singleplayer;
    public long startedAt;
    public int durationTicks;
    public String minecraftVersion = "";
    public String modVersion = "";
    /** Loader the recording was made on (neoforge, forge, fabric). Empty in recordings made before this field existed. */
    public String loader = "";
    /** Minecraft version the recording was made on. Empty in recordings made before this field existed. */
    public String mc = "";
    public String recorderName = "";
    public String recorderUuid = "";
    /** UUID given to the entity that represents the recording player during playback. */
    public String actorUuid = "";
    public List<String> mods = new ArrayList<>();
    /**
     * Distant Horizons level folders of the dimensions visited while recording: dimension id to the folder holding
     * DH's database, relative to the game folder. Empty for recordings made without DH or before this field existed.
     */
    public Map<String, String> distantHorizons = new ConcurrentHashMap<>();
    /**
     * Game time of the server of a singleplayer recording (the client's otherwise) at tick 0, or -1 when unknown. An
     * event scheduled at game time {@code g} happens at replay tick {@code g - startGameTime}.
     */
    public long startGameTime = -1;
    /**
     * Scene jobs: replay tick at which the step's commands ran, negative since they run before the recording starts.
     * Null outside scene jobs and in recordings made before this field existed.
     */
    public Integer sceneCommandsTick;
}
