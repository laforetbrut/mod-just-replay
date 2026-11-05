package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.fabric.mixin.KeyMappingClickAccessor;
import com.vyrriox.justreplay.mixin.KeyMappingAccessor;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Playback keys on Fabric, which has no key conflict context. A playback key (J, G...) in the vanilla map would count
 * presses in a normal game and fire them all when a replay starts. These bindings stay out of the map and
 * receive the key presses from a mixin, only inside a replay and without a screen open.
 *
 * @author vyrriox
 */
public final class FabricReplayKeys {
    private static final List<KeyMapping> KEYS = new ArrayList<>();

    private FabricReplayKeys() {
    }

    static KeyMapping create(String name, InputConstants.Type type, int key, KeyMapping.Category category) {
        KeyMapping mapping = new KeyMapping(name, type, key, category) {
            @Override
            public boolean isDown() {
                return super.isDown() && active();
            }
        };
        KEYS.add(mapping);
        return mapping;
    }

    private static boolean active() {
        return ReplaySession.isRunning() && Minecraft.getInstance().screen == null;
    }

    private static boolean bound(KeyMapping mapping, InputConstants.Key key) {
        return key != InputConstants.UNKNOWN && ((KeyMappingAccessor) mapping).justreplay$getKey().equals(key);
    }

    /** Takes these bindings out of the vanilla map, which vanilla fills again from all the bindings. */
    public static void hide(Map<InputConstants.Key, List<KeyMapping>> map) {
        for (List<KeyMapping> mappings : map.values()) {
            mappings.removeAll(KEYS);
        }
    }

    public static void click(InputConstants.Key key) {
        if (!active()) {
            return;
        }
        for (KeyMapping mapping : KEYS) {
            if (bound(mapping, key)) {
                KeyMappingClickAccessor counter = (KeyMappingClickAccessor) mapping;
                counter.justreplay$setClickCount(counter.justreplay$getClickCount() + 1);
            }
        }
    }

    public static void set(InputConstants.Key key, boolean down) {
        if (down && !active()) {
            return;
        }
        for (KeyMapping mapping : KEYS) {
            if (bound(mapping, key)) {
                mapping.setDown(down);
            }
        }
    }
}
