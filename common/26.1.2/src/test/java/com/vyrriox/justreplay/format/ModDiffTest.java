package com.vyrriox.justreplay.format;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mods of a recording against the installed ones: what is missing, added or updated, and what never counts.
 *
 * @author vyrriox
 */
class ModDiffTest {
    @Test
    void sameModsGiveNothing() {
        ModDiff diff = ModDiff.compare(List.of("create@6.0.10", "jei@19.21"), List.of("jei@19.21", "create@6.0.10"));
        assertTrue(diff.isEmpty());
        assertEquals(0, diff.size());
        assertTrue(diff.lines().isEmpty());
    }

    @Test
    void missingAddedAndUpdatedModsAreSortedById() {
        ModDiff diff = ModDiff.compare(List.of("zeta@1", "create@6.0.9", "alpha@2", "jei@19.21"),
                List.of("jei@19.21", "create@6.0.10", "sodium@0.6", "beta@1"));
        assertEquals(List.of(new ModDiff.Mod("alpha", "2"), new ModDiff.Mod("zeta", "1")), diff.missing());
        assertEquals(List.of(new ModDiff.Mod("beta", "1"), new ModDiff.Mod("sodium", "0.6")), diff.added());
        assertEquals(List.of(new ModDiff.Changed("create", "6.0.9", "6.0.10")), diff.changed());
        assertEquals(5, diff.size());
        assertEquals(List.of("missing: alpha 2", "missing: zeta 1", "added: beta 1", "added: sodium 0.6",
                "updated: create 6.0.9 -> 6.0.10"), diff.lines());
    }

    @Test
    void justReplayTheGameAndTheLoadersNeverCount() {
        ModDiff diff = ModDiff.compare(
                List.of("justreplay@1.1.1", "minecraft@1.21.1", "neoforge@21.1.221", "java@21", "mixinextras@0.4.1"),
                List.of("justreplay@1.1.2", "minecraft@1.21.1", "neoforge@21.1.228", "java@25", "forge@52.1.0",
                        "fabricloader@0.16.10", "mixinextras@0.5.0"));
        assertTrue(diff.isEmpty());
        assertTrue(ModDiff.ignored("fabric-loader"));
        assertFalse(ModDiff.ignored("create"));
    }

    @Test
    void fabricApiCountsButNotItsModules() {
        ModDiff diff = ModDiff.compare(
                List.of("fabric-api@0.115.0", "fabric-networking-api-v1@4.3.0", "fabric-api-base@0.4.50",
                        "fabric-renderer-indigo@2.0.1", "fabric-language-kotlin@1.13.0"),
                List.of("fabric-api@0.116.1", "fabric-networking-api-v1@4.4.0", "fabric-api-base@0.4.51",
                        "fabric-renderer-indigo@2.0.2", "fabric-language-kotlin@1.13.0"));
        assertEquals(List.of(new ModDiff.Changed("fabric-api", "0.115.0", "0.116.1")), diff.changed());
        assertEquals(1, diff.size());
        assertFalse(ModDiff.ignored("fabric-language-kotlin"));
    }

    @Test
    void recordingWithoutAListOpensWithoutWarning() {
        assertTrue(ModDiff.compare(List.of(), List.of("create@6.0.10")).isEmpty());
        assertTrue(ModDiff.compare(null, List.of("create@6.0.10")).isEmpty());
        // Only ignored mods recorded: nothing to compare either.
        assertTrue(ModDiff.compare(List.of("minecraft@1.21.1", "justreplay@1.1.2"), List.of("create@6.0.10")).isEmpty());
    }

    @Test
    void emptyInstalledListMakesEveryRecordedModMissing() {
        ModDiff diff = ModDiff.compare(List.of("create@6.0.10"), List.of());
        assertEquals(List.of(new ModDiff.Mod("create", "6.0.10")), diff.missing());
        assertTrue(ModDiff.compare(List.of("create@6.0.10"), null).added().isEmpty());
    }

    @Test
    void oddEntriesAreRead() {
        ModDiff diff = ModDiff.compare(List.of(" Create @ 6.0.10 ", "lonely", "", "  ", "@1.0"),
                List.of("create@6.0.10", "lonely@2"));
        // Ids are compared in lower case; an entry without a version has an empty one; an entry without an id is skipped.
        assertEquals(List.of(new ModDiff.Changed("lonely", "", "2")), diff.changed());
        assertEquals("lonely", ModDiff.label(new ModDiff.Mod("lonely", "")));
        assertEquals("create 6.0.10", ModDiff.label(new ModDiff.Mod("create", "6.0.10")));
    }

    @Test
    void lastEntryWinsWhenAnIdComesTwice() {
        ModDiff diff = ModDiff.compare(List.of("create@6.0.9", "create@6.0.10"), List.of("create@6.0.10"));
        assertTrue(diff.isEmpty());
    }
}
