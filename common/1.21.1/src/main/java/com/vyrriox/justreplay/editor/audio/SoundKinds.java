package com.vyrriox.justreplay.editor.audio;

import com.vyrriox.justreplay.editor.ReplayProject;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/**
 * The kinds of game sound a project can turn up or down on their own: footsteps (quiet in the game, a step plays at
 * 15 percent of its volume, so they start at 200 percent), then the game's own sound categories.
 *
 * @author vyrriox
 */
public final class SoundKinds {
    public static final String FOOTSTEPS = "footsteps";
    /** Every kind, in the order the editor shows them. */
    public static final List<String> ALL = List.of(FOOTSTEPS, "players", "blocks", "hostile", "neutral", "ambient", "weather", "records");
    public static final float MAX = 4.0F;
    /**
     * A footstep plays at 15 percent of its volume and fades with the distance: heard from a camera a few blocks away
     * it is nearly silent, so replays and exports play footsteps twice as loud unless the project says otherwise.
     */
    public static final float FOOTSTEPS_DEFAULT = 2.0F;

    private SoundKinds() {
    }

    /** The kind of a sound: footsteps by their name, everything else by its category; null for a kind not handled. */
    public static String of(SoundInstance sound) {
        String path = sound.getLocation().getPath();
        if (path.endsWith(".step")) {
            return FOOTSTEPS;
        }
        SoundSource source = sound.getSource();
        return switch (source) {
            case PLAYERS -> "players";
            case BLOCKS -> "blocks";
            case HOSTILE -> "hostile";
            case NEUTRAL -> "neutral";
            case AMBIENT -> "ambient";
            case WEATHER -> "weather";
            case RECORDS -> "records";
            default -> null;
        };
    }

    /** The volume of a kind when the project does not set it. */
    public static float defaultGain(String kind) {
        return FOOTSTEPS.equals(kind) ? FOOTSTEPS_DEFAULT : 1.0F;
    }

    public static float gain(ReplayProject project, String kind) {
        if (kind == null) {
            return 1.0F;
        }
        Float g = project.soundGains == null ? null : project.soundGains.get(kind);
        return g == null ? defaultGain(kind) : Math.max(0.0F, Math.min(MAX, g));
    }

    /** The project's volume for the kind of this sound (1 when the project does not change it). */
    public static float gain(ReplayProject project, SoundInstance sound) {
        return gain(project, of(sound));
    }
}
