package com.vyrriox.justreplay.platform;

/**
 * When a key binding of the mod acts, whatever the loader does with that information.
 *
 * @author vyrriox
 */
public enum KeyScope {
    /** In a world with no screen open. */
    IN_GAME,
    /** As {@link #IN_GAME}, and only while a replay is open: these keys never steal keys from other mods in a normal game. */
    IN_REPLAY
}
