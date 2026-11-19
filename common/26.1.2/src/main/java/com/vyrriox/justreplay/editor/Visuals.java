package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The light, fog and particle settings of the World tab while a replay runs: the same answers for live playback,
 * previews and video exports. Every query bails out early outside a replay and with the settings left as recorded.
 *
 * @author vyrriox
 */
public final class Visuals {
    /** Brightness that lights every block fully (the game's own brightness setting goes up to 1). */
    public static final float FULL_BRIGHT = 16.0F;
    /** Particle type of the bits of a block being broken. */
    public static final String BLOCK_PARTICLES = "minecraft:block";

    /** Particles not shown, for the self test. */
    private static int hiddenParticles;

    private Visuals() {
    }

    private static WorldSettings world() {
        ReplaySession s = ReplaySession.current();
        return s != null && ReplaySession.isRunning() ? s.project().world : null;
    }

    public static boolean nightVision() {
        WorldSettings w = world();
        return w != null && w.nightVision;
    }

    /** The fog chosen in the World tab, or null for the game's own fog. */
    public static String fog() {
        WorldSettings w = world();
        return w == null || WorldSettings.FOG_NORMAL.equals(w.fog) ? null : w.fog;
    }

    /** True when a particle of this type must not be created. */
    public static boolean particleHidden(ParticleOptions options) {
        WorldSettings w = world();
        if (w == null || WorldSettings.PARTICLES_ALL.equals(w.particles)) {
            return false;
        }
        boolean hidden = WorldSettings.PARTICLES_NONE.equals(w.particles)
                || w.particleHidden(String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType())));
        if (hidden) {
            hiddenParticles++;
        }
        return hidden;
    }

    /** True when no particle at all is shown, whatever made it. */
    public static boolean noParticles() {
        WorldSettings w = world();
        if (w != null && WorldSettings.PARTICLES_NONE.equals(w.particles)) {
            hiddenParticles++;
            return true;
        }
        return false;
    }

    /** True when the bits of broken and cracking blocks are hidden. */
    public static boolean blockParticlesHidden() {
        WorldSettings w = world();
        return w != null && w.particleHidden(BLOCK_PARTICLES);
    }

    public static int hiddenParticles() {
        return hiddenParticles;
    }
}
