package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markers of the editor: saved in {@code project.json} and read back unchanged, older projects still load, invalid
 * ranges and places are refused.
 *
 * @author vyrriox
 */
class MarkerTest {
    @TempDir
    Path dir;

    private static String describe(Marker m) {
        return m.type + " " + m.name + " @" + m.tick + (m.isRange() ? ".." + m.end : "")
                + (m.isPoint() ? " " + m.x + "," + m.y + "," + m.z + " in " + m.dimension : "")
                + (m.isEntity() ? " on " + m.target + " (" + m.targetName + ")" : "")
                + String.format(" #%06X", m.color) + " [" + m.note + "] " + m.id;
    }

    private static List<String> describe(List<Marker> markers) {
        return markers.stream().map(MarkerTest::describe).toList();
    }

    private ReplayProject reload(ReplayProject project) {
        ReplayStorage.writeProject(dir, project);
        return ReplayStorage.readProject(dir);
    }

    private ReplayProject readFile(String json) throws IOException {
        Files.writeString(dir.resolve(ReplayStorage.PROJECT), json, StandardCharsets.UTF_8);
        return ReplayStorage.readProject(dir);
    }

    @Test
    void everyKindComesBackFromTheFileAsItWasSaved() {
        ReplayProject project = new ReplayProject();
        project.normalize();
        Marker intro = project.addMarker(Marker.instant(40, "Intro"));
        intro.note = "Start the first camera here.\nSlow and wide.";
        Marker fight = project.addMarker(Marker.range(200, 460, 1200, "Fight"));
        fight.color = 0x5AB4FF;
        Marker tower = project.addMarker(Marker.point(300, 12.5, 70.0, -8.25, "minecraft:the_nether", "Tower"));
        tower.note = "Orbit around it, \"slowly\" & from above";
        tower.setRange(300, 420, 1200);
        Marker hero = project.addMarker(Marker.entity(500, "1b2c3d4e-0000-4000-8000-00000000abcd", "Steve", "Hero"));
        List<String> before = describe(project.markers);

        ReplayProject loaded = reload(project);
        assertEquals(before, describe(loaded.markers));
        assertEquals(4, loaded.markers.size());
        assertEquals(List.of(intro.id, fight.id, tower.id, hero.id), loaded.markers.stream().map(m -> m.id).toList());
        assertEquals("Start the first camera here.\nSlow and wide.", loaded.marker(intro.id).note);
        assertTrue(loaded.showMarkers);
        // A second round trip changes nothing either: the ids stay.
        assertEquals(before, describe(reload(loaded).markers));
    }

    @Test
    void aProjectWithoutMarkersLoadsAsBefore() throws IOException {
        // Written before markers existed at all: no "markers" key, no "showMarkers".
        ReplayProject old = readFile("""
                {
                  "showPath": false,
                  "cameras": [{"id": "cam-1", "name": "Camera 1", "type": "PATH",
                               "keyframes": [{"tick": 0, "x": 1.0, "y": 64.0, "z": 2.0, "fov": 70.0},
                                             {"tick": 80, "x": 5.0, "y": 64.0, "z": 2.0, "fov": 70.0}]}],
                  "selectedCamera": "cam-1",
                  "gameVolume": 0.5
                }
                """);
        assertTrue(old.markers.isEmpty());
        assertTrue(old.showMarkers);
        assertFalse(old.showPath);
        assertEquals(0.5F, old.gameVolume);
        assertEquals(2, old.camera("cam-1").keyframes.size());
        assertEquals("cam-1", old.selected().id);
        // A missing project file still gives an empty project.
        Files.delete(dir.resolve(ReplayStorage.PROJECT));
        assertTrue(ReplayStorage.readProject(dir).markers.isEmpty());
    }

    @Test
    void markersOfAnOlderProjectBecomeMomentsWithStableIds() throws IOException {
        String json = """
                {"markers": [{"tick": 40, "name": "Intro"}, {"tick": 40, "name": "Intro"}, {"tick": 300, "name": "Ending"}],
                 "recordingMarkersImported": true}
                """;
        ReplayProject first = readFile(json);
        ReplayProject second = readFile(json);
        assertEquals(3, first.markers.size());
        for (Marker m : first.markers) {
            assertEquals(Marker.INSTANT, m.type);
            assertEquals(-1, m.end);
            assertEquals(Marker.defaultColor(Marker.INSTANT), m.color);
            assertEquals("", m.note);
            assertFalse(m.id.isBlank());
        }
        // The same file gives the same ids, even for two markers alike, and no two ids are equal.
        assertEquals(describe(first.markers), describe(second.markers));
        assertNotEquals(first.markers.get(0).id, first.markers.get(1).id);
        // Once saved, the ids are written and stay.
        List<String> ids = first.markers.stream().map(m -> m.id).toList();
        assertEquals(ids, reload(first).markers.stream().map(m -> m.id).toList());
    }

    @Test
    void invalidRangesAreRefused() {
        assertEquals(Marker.RangeCheck.OK, Marker.checkRange(0, 100, 100));
        assertEquals(Marker.RangeCheck.OK, Marker.checkRange(10, 5000, -1));
        assertEquals(Marker.RangeCheck.NEGATIVE_START, Marker.checkRange(-1, 10, 100));
        assertEquals(Marker.RangeCheck.EMPTY, Marker.checkRange(10, 10, 100));
        assertEquals(Marker.RangeCheck.EMPTY, Marker.checkRange(50, 10, 100));
        assertEquals(Marker.RangeCheck.PAST_END, Marker.checkRange(10, 101, 100));
        assertThrows(IllegalArgumentException.class, () -> Marker.range(50, 10, 100, "Backwards"));
        assertThrows(IllegalArgumentException.class, () -> Marker.range(-5, 10, 100, "Before"));
        assertThrows(IllegalArgumentException.class, () -> Marker.range(20, 20, 100, "Empty"));
        assertThrows(IllegalArgumentException.class, () -> Marker.range(20, 400, 100, "Too long"));

        // A refused range leaves the marker as it was.
        Marker m = Marker.instant(30, "Moment");
        assertEquals(Marker.RangeCheck.EMPTY, m.setRange(30, 10, 100));
        assertEquals(Marker.RangeCheck.PAST_END, m.setRange(30, 200, 100));
        assertEquals(Marker.INSTANT, m.type);
        assertEquals(30, m.tick);
        assertEquals(-1, m.end);
        // A valid one turns the moment into a range, and clearing the end turns it back.
        assertEquals(Marker.RangeCheck.OK, m.setRange(30, 90, 100));
        assertEquals(Marker.RANGE, m.type);
        assertEquals(60, m.length());
        m.clearEnd();
        assertEquals(Marker.INSTANT, m.type);
        assertFalse(m.isRange());
    }

    @Test
    void brokenMarkersInAFileAreRepaired() throws IOException {
        ReplayProject p = readFile("""
                {"markers": [
                  {"id": "a", "type": "RANGE", "tick": 100, "end": 40, "name": "Backwards range"},
                  {"id": "b", "type": "INSTANT", "tick": -20, "end": 60, "name": "Has an end"},
                  {"id": "c", "type": "POINT", "tick": 10, "name": "Point", "x": 1.0, "y": 2.0, "z": 3.0},
                  {"id": "d", "type": "ENTITY", "tick": 10, "name": "No target", "target": "  "},
                  {"id": "a", "type": "WHATEVER", "tick": 5, "name": null, "note": null, "dimension": null},
                  null
                ]}
                """);
        assertEquals(5, p.markers.size());
        assertEquals(List.of(0, 5, 10, 10, 100), p.markers.stream().map(m -> m.tick).toList());
        Marker backwards = p.markers.get(4);
        assertEquals("Backwards range", backwards.name);
        assertEquals(Marker.INSTANT, backwards.type);
        assertEquals(-1, backwards.end);
        Marker hasEnd = p.marker("b");
        assertEquals(Marker.RANGE, hasEnd.type);
        assertEquals(60, hasEnd.end);
        assertEquals(Marker.POINT, p.marker("c").type);
        assertEquals("", p.marker("c").dimension);
        assertEquals(Marker.INSTANT, p.marker("d").type);
        assertEquals("", p.marker("d").target);
        // The repeated id "a" got a new one; the unknown type became a moment, null texts empty ones.
        Marker repeated = p.markers.get(1);
        assertNotEquals("a", repeated.id);
        assertEquals("a", backwards.id);
        assertEquals(Marker.INSTANT, repeated.type);
        assertEquals("", repeated.name);
        assertEquals("", repeated.note);
    }

    @Test
    void placesAndTargetsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> Marker.point(0, Double.NaN, 64, 0, "minecraft:overworld", "Nowhere"));
        assertThrows(IllegalArgumentException.class, () -> Marker.point(0, 0, Double.POSITIVE_INFINITY, 0, "", "Nowhere"));
        assertThrows(IllegalArgumentException.class, () -> Marker.entity(0, " ", "", "Nobody"));
        assertThrows(IllegalArgumentException.class, () -> Marker.entity(0, null, "", "Nobody"));

        Marker m = Marker.instant(10, "Moving");
        m.placeAt(1, 2, 3, " minecraft:overworld ");
        assertEquals(Marker.POINT, m.type);
        assertEquals("minecraft:overworld", m.dimension);
        assertEquals(Marker.defaultColor(Marker.POINT), m.color);
        m.follow(Marker.ACTOR_PREFIX + "abc", "Alex");
        assertEquals(Marker.ENTITY, m.type);
        assertEquals(0.0, m.x);
        assertEquals("", m.dimension);
        m.color = 0x123456;
        m.unpin();
        assertEquals(Marker.INSTANT, m.type);
        assertEquals("", m.target);
        // A colour chosen by hand stays when the type changes.
        assertEquals(0x123456, m.color);

        // A point read with a broken position keeps its time only.
        Marker broken = new Marker();
        broken.type = Marker.POINT;
        broken.x = Double.NaN;
        broken.normalize();
        assertEquals(Marker.INSTANT, broken.type);
        assertEquals(0.0, broken.x);
    }

    @Test
    void movingKeepsTheLengthInsideTheReplay() {
        Marker range = Marker.range(100, 160, 1000, "Range");
        assertTrue(range.moveTo(500, 1000));
        assertEquals(500, range.tick);
        assertEquals(560, range.end);
        assertTrue(range.moveTo(980, 1000));
        assertEquals(940, range.tick);
        assertEquals(1000, range.end);
        assertTrue(range.moveTo(-50, 1000));
        assertEquals(0, range.tick);
        assertEquals(60, range.end);
        assertFalse(range.moveTo(0, 1000));
        Marker moment = Marker.instant(5, "Moment");
        assertTrue(moment.moveTo(2000, -1));
        assertEquals(2000, moment.tick);
        assertEquals(-1, moment.end);
        assertTrue(moment.activeAt(2000, 0));
        assertTrue(moment.activeAt(1990, 10));
        assertFalse(moment.activeAt(1989, 10));
    }

    @Test
    void idsAreFoundKeptAndNeverShared() {
        ReplayProject p = new ReplayProject();
        p.normalize();
        Marker a = p.addMarker(Marker.instant(50, p.nextMarkerName()));
        Marker b = p.addMarker(Marker.instant(10, p.nextMarkerName()));
        assertEquals("Marker 1", a.name);
        assertEquals("Marker 2", b.name);
        assertEquals(List.of(b, a), p.markers);
        assertSame(a, p.marker(a.id));
        assertNull(p.marker("missing"));
        assertNull(p.marker(null));
        Marker clash = new Marker();
        clash.id = a.id;
        p.addMarker(clash);
        assertNotEquals(a.id, clash.id);
        assertFalse(clash.id.isBlank());
        Marker copy = a.copy();
        assertNotEquals(a.id, copy.id);
        assertEquals(describe(a).replace(a.id, ""), describe(copy).replace(copy.id, ""));
        assertTrue(p.removeMarker(a.id));
        assertFalse(p.removeMarker(a.id));
        assertNull(p.marker(a.id));
        assertEquals(2, p.markers.size());
        assertEquals("Marker 3", p.nextMarkerName());
    }

    @Test
    void recordingMarkersArriveOnceAsMoments() {
        ReplayProject p = new ReplayProject();
        p.normalize();
        List<ReplayMeta.Marker> recorded = new ArrayList<>(Arrays.asList(new ReplayMeta.Marker(90, "Marker 2"),
                new ReplayMeta.Marker(-3, ""), null));
        assertTrue(p.importRecordingMarkers(recorded));
        assertEquals(List.of(0, 90), p.markers.stream().map(m -> m.tick).toList());
        // A recorded marker without a name gets a free default one.
        assertEquals(List.of("Marker 3", "Marker 2"), p.markers.stream().map(m -> m.name).toList());
        for (Marker m : p.markers) {
            assertEquals(Marker.INSTANT, m.type);
            assertFalse(m.id.isBlank());
        }
        assertFalse(p.importRecordingMarkers(recorded));
        assertEquals(2, p.markers.size());
    }

    @Test
    void timeEditsMoveMarkersAndTheirEnds() {
        ReplayProject p = new ReplayProject();
        p.normalize();
        Marker before = p.addMarker(Marker.instant(10, "Before"));
        Marker across = p.addMarker(Marker.range(80, 140, -1, "Across"));
        Marker inside = p.addMarker(Marker.range(110, 130, -1, "Inside"));
        Marker after = p.addMarker(Marker.point(200, 0, 64, 0, "minecraft:overworld", "After"));
        TimeShift.insert(p, 100, 40);
        assertEquals(10, before.tick);
        assertEquals(80, across.tick);
        assertEquals(180, across.end);
        assertEquals(150, inside.tick);
        assertEquals(170, inside.end);
        assertEquals(240, after.tick);
        // Removing ticks 100 to 179 takes the range inside them and shortens the one across them.
        TimeShift.remove(p, 100, 80);
        assertEquals(List.of(before, across, after), p.markers);
        assertEquals(80, across.tick);
        assertEquals(100, across.end);
        assertEquals(160, after.tick);
        TimeShift.remove(p, 81, 30);
        assertEquals(80, across.tick);
        assertEquals(81, across.end);
        assertTrue(across.isRange());
    }
}
