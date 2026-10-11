package com.vyrriox.justreplay;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point of Just Replay.
 *
 * @author vyrriox
 */
@Mod(JustReplay.MOD_ID)
public final class JustReplay {
    public static final String MOD_ID = "justreplay";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JustReplay(IEventBus modEventBus) {
        LOGGER.info("Just Replay initialised");
    }
}
