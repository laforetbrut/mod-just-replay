package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.config.ConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The integration switches are read straight from the client config, before the game starts.
 *
 * @author vyrriox
 */
class CompatSwitchesTest {
    @Test
    void readsTheSwitchesOfTheConfigFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(CompatSwitches.FILE_NAME);
        Files.writeString(file, """
                // Just Replay client settings.
                {
                  "recording": {
                    "autoRecordMultiplayer": false
                  },
                  // Integrations with other mods.
                  "compat": {
                    // GeckoLib integration.
                    "geckolib": false,
                    "jade": true,
                    "curios": "maybe"
                  }
                }
                """, StandardCharsets.UTF_8);
        Map<String, Boolean> switches = CompatSwitches.read(file);
        assertEquals(Boolean.FALSE, switches.get("geckolib"));
        assertEquals(Boolean.TRUE, switches.get("jade"));
        assertFalse(switches.containsKey("curios"), "a value that is not a boolean keeps the integration on");
    }

    @Test
    void missingOrBrokenFileTurnsNothingOff(@TempDir Path dir) throws IOException {
        assertTrue(CompatSwitches.read(dir.resolve("missing.json")).isEmpty());
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ \"compat\": { \"geckolib\": fal", StandardCharsets.UTF_8);
        assertTrue(CompatSwitches.read(broken).isEmpty());
        assertTrue(CompatSwitches.parse("[1, 2]").isEmpty());
        assertTrue(CompatSwitches.parse("{\"compat\": 3}").isEmpty());
        assertEquals(Boolean.FALSE, CompatSwitches.parse("﻿{\"compat\": {\"jade\": false}}").get("jade"));
    }

    @Test
    void readsWhatTheConfigWrites(@TempDir Path dir) {
        Path file = dir.resolve(CompatSwitches.FILE_NAME);
        ConfigFile config = new ConfigFile(file, "Just Replay client settings.");
        config.section("playback", "Watching replays.").bool("cleanHud", true, "Hide the HUD.");
        ConfigFile.Section compat = config.section(CompatSwitches.SECTION, "Integrations with other mods.");
        ConfigFile.BoolValue geckolib = compat.bool("geckolib", true, "GeckoLib integration.");
        compat.bool("jade", true, "Jade integration.");
        config.load();
        geckolib.set(false);

        Map<String, Boolean> switches = CompatSwitches.read(file);
        assertEquals(Boolean.FALSE, switches.get("geckolib"));
        assertEquals(Boolean.TRUE, switches.get("jade"));
    }
}
