package com.vyrriox.justreplay.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every compat mixin belongs to an integration (so that its switch removes it), and every integration has a name
 * and a switch.
 *
 * @author vyrriox
 */
class CompatTargetsTest {
    @Test
    void everyCompatMixinBelongsToAnIntegration() throws Exception {
        JsonObject config;
        try (InputStream in = CompatTargetsTest.class.getResourceAsStream("/justreplay.compat.mixins.json")) {
            assertNotNull(in, "justreplay.compat.mixins.json is on the classpath");
            config = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals("com.vyrriox.justreplay.mixin.compat.CompatMixinPlugin", config.get("plugin").getAsString());
        assertEquals(0, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        int checked = 0;
        for (JsonElement element : config.getAsJsonArray("client")) {
            String mixin = element.getAsString();
            String owner = CompatTargets.mixinOwner(config.get("package").getAsString() + "." + mixin);
            if (owner == null) {
                assertTrue(CompatTargets.GAME_MIXINS.contains(mixin), mixin + " belongs to no integration");
            } else {
                assertTrue(CompatTargets.NAMES.containsKey(owner), mixin + " belongs to an unknown integration " + owner);
            }
            checked++;
        }
        assertTrue(checked > 0, "the compat config lists mixins");
    }

    @Test
    void everySupportedModHasANameAndASwitch() {
        for (String modId : CompatTargets.supportedAnywhere()) {
            assertTrue(CompatTargets.NAMES.containsKey(modId), modId + " has no name in CompatTargets.NAMES");
        }
        assertTrue(CompatTargets.NAMES.containsKey(CompatTargets.VOICE_CHAT));
    }

    @Test
    void mixinOwnerReadsTheClassName() {
        assertEquals("geckolib", CompatTargets.mixinOwner("com.vyrriox.justreplay.mixin.compat.GeckoLibClockMixin"));
        assertNull(CompatTargets.mixinOwner("com.vyrriox.justreplay.mixin.compat.LevelRendererClockMixin"));
    }

    @Test
    void mainMixinConfigToleratesChangedMethods() throws Exception {
        try (InputStream in = CompatTargetsTest.class.getResourceAsStream("/justreplay.mixins.json")) {
            assertNotNull(in, "justreplay.mixins.json is on the classpath");
            JsonObject config = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            // Another mod that changed a method we inject into must not stop the game from starting.
            assertEquals(0, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        }
    }
}
