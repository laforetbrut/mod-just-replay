package com.vyrriox.justreplay.format;

import java.util.ArrayList;
import java.util.List;

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
}
