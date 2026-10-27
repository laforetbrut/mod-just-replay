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
}
