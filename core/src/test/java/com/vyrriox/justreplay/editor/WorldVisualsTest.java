package com.vyrriox.justreplay.editor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light, fog and particle settings of the World tab, and the entity types and outlines of the Entities tab, as a
 * project keeps them.
 *
 * @author vyrriox
 */
class WorldVisualsTest {
    @Test
    void defaultsChangeNothing() {
        WorldSettings w = new WorldSettings();
        assertFalse(w.visualsActive());
        assertFalse(w.particleHidden("minecraft:smoke"));
        assertArrayEquals(new float[]{230.0F, 256.0F}, WorldSettings.fogDistances(WorldSettings.FOG_NORMAL, 230.0F, 256.0F, 256.0F));
    }

    @Test
    void particleModes() {
        WorldSettings w = new WorldSettings();
        w.particles = WorldSettings.PARTICLES_NONE;
        assertTrue(w.particleHidden("minecraft:smoke"));
        assertTrue(w.particleHidden(null));
        w.particles = WorldSettings.PARTICLES_CHOSEN;
        w.toggleParticle("minecraft:smoke");
        assertTrue(w.particleHidden("minecraft:smoke"));
        assertFalse(w.particleHidden("minecraft:flame"));
        assertFalse(w.particleHidden(null));
        w.toggleParticle("minecraft:smoke");
        assertFalse(w.particleHidden("minecraft:smoke"));
        assertTrue(w.visualsActive());
    }

    @Test
    void toggledListsStaySortedWithoutDuplicates() {
        WorldSettings w = new WorldSettings();
        w.toggleParticle("minecraft:smoke");
        w.toggleParticle("create:steam");
        w.toggleParticle("  ");
        w.toggleParticle(null);
        assertEquals(List.of("create:steam", "minecraft:smoke"), w.hiddenParticles);
        assertEquals(List.of("a:b", "c:d"), WorldSettings.cleanIds(Arrays.asList(" c:d ", "a:b", null, "", "c:d")));
        assertTrue(WorldSettings.cleanIds(null).isEmpty());
    }

    @Test
    void fogOffAndReduced() {
        float[] off = WorldSettings.fogDistances(WorldSettings.FOG_OFF, 230.0F, 256.0F, 256.0F);
        assertTrue(off[0] >= 100000.0F && off[1] >= 100000.0F);
        // Distance fog: the end stays at the view distance, only the far half of the fade is kept.
        assertArrayEquals(new float[]{243.0F, 256.0F}, WorldSettings.fogDistances(WorldSettings.FOG_REDUCED, 230.0F, 256.0F, 256.0F));
        // Thick fog of a biome: twice as far, up to the view distance.
        assertArrayEquals(new float[]{106.4F, 192.0F}, WorldSettings.fogDistances(WorldSettings.FOG_REDUCED, 20.8F, 96.0F, 256.0F), 0.001F);
        assertArrayEquals(new float[]{66.0F, 128.0F}, WorldSettings.fogDistances(WorldSettings.FOG_REDUCED, 4.0F, 96.0F, 128.0F), 0.001F);
    }

    @Test
    void normalizeRepairsHandEditedSettings() {
        WorldSettings w = new WorldSettings();
        w.fog = "THICK";
        w.particles = null;
        w.weather = null;
        w.hiddenParticles = new ArrayList<>(Arrays.asList("b:x", null, "a:y", "b:x"));
        w.normalize();
        assertEquals(WorldSettings.WEATHER_RECORDED, w.weather);
        assertEquals(WorldSettings.FOG_NORMAL, w.fog);
        assertEquals(WorldSettings.PARTICLES_ALL, w.particles);
        assertEquals(List.of("a:y", "b:x"), w.hiddenParticles);
    }

    @Test
    void hiddenTypesAndOutlinesLiveInTheProject() {
        ReplayProject project = new ReplayProject();
        project.toggleHiddenType("minecraft:zombie");
        project.toggleHiddenType("minecraft:bat");
        project.toggleHiddenType("");
        assertEquals(List.of("minecraft:bat", "minecraft:zombie"), project.hiddenEntityTypes);
        project.toggleHiddenType("minecraft:bat");
        assertEquals(List.of("minecraft:zombie"), project.hiddenEntityTypes);
        project.hiddenEntityTypes = null;
        project.normalize();
        assertTrue(project.hiddenEntityTypes.isEmpty());

        ReplayProject.EntityEdit edit = new ReplayProject.EntityEdit("uuid", "Zombie");
        assertTrue(edit.isEmpty());
        edit.glow = true;
        // An outline alone is an edit worth keeping.
        assertFalse(edit.isEmpty());
    }
}
