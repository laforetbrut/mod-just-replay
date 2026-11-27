package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayMeta;

import java.nio.file.Path;

/**
 * What the read tools need from the game, given by the game side. The first two methods are only called on the game
 * thread; what they return is read there and nowhere else.
 *
 * @author vyrriox
 */
public interface McpGame {
    /** Game thread: what the mod is doing now. */
    McpState state();

    /** Game thread: the replay open in the game (watched or edited), with its live project, or null. */
    OpenReplay openReplay();

    /** Any thread: the folder of the saved replays. */
    Path replaysRoot();

    /**
     * The open replay.
     *
     * @param id            its folder name, as {@code list_replays} gives it
     * @param tick          where the play head is
     * @param durationTicks length of the replay
     */
    record OpenReplay(String id, ReplayMeta meta, ReplayProject project, int tick, int durationTicks) {
    }
}
