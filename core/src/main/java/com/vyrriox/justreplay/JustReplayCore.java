package com.vyrriox.justreplay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constants of the mod that need no game class, for the plain Java code of {@code core/}. {@code JustReplay} hands the
 * same values to the code that uses the game.
 *
 * @author vyrriox
 */
public final class JustReplayCore {
    public static final String MOD_ID = "justreplay";
    /** The mod's logger, under the name of {@code JustReplay} so that log lines read as before. */
    public static final Logger LOGGER = LoggerFactory.getLogger("com.vyrriox.justreplay.JustReplay");
    /** The client config file, in the loader's config folder. */
    public static final String CLIENT_CONFIG_FILE = "justreplay-client.json";

    private JustReplayCore() {
    }
}
