package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filters, order and pages of the entity lists, the names an entity answers to and the JSON it gives.
 *
 * @author vyrriox
 */
class McpEntityQueryTest {
    private final FakeObserver world = new FakeObserver();

    private List<Integer> ids(McpEntityQuery.Page page) {
        List<Integer> ids = new ArrayList<>();
        for (McpEntity e : page.entities()) {
            ids.add(e.id);
        }
        return ids;
    }

    private static McpEntityQuery query(List<String> types, String source, String text) {
        return new McpEntityQuery(types, source, text, null, 0.0, 0, 50);
    }

    @Test
    void typesMatchFullIdsNamesAndWholeMods() {
        List<McpEntity> all = world.entities();
        assertEquals(List.of(1), ids(query(List.of("minecraft:pig"), null, "").apply(all)));
        assertEquals(List.of(1), ids(query(List.of("PIG"), null, "").apply(all)));
        assertEquals(List.of(-1000, 3), ids(query(List.of("player"), null, "").apply(all)));
        assertEquals(all.size(), query(List.of("minecraft:*"), null, "").apply(all).total());
        assertEquals(0, query(List.of("create:*"), null, "").apply(all).total());
        assertEquals(all.size(), query(List.of(), null, "").apply(all).total());
    }

    @Test
    void sourceAndNameFilter() {
        List<McpEntity> all = world.entities();
        assertEquals(List.of(-1000), ids(query(List.of(), McpEntity.ACTOR, "").apply(all)));
        assertFalse(ids(query(List.of(), McpEntity.RECORDED, "").apply(all)).contains(-1000));
        assertEquals(List.of(2), ids(query(List.of(), null, "villag").apply(all)));
        assertEquals(List.of(11), ids(query(List.of(), null, "ZOMBIE").apply(all)));
    }

    @Test
    void aSphereKeepsWhatIsInsideNearestFirst() {
        List<McpEntity> all = world.entities();
        // The pig is at x 14 at tick 40, the villager 34 blocks away.
        McpEntityQuery around = new McpEntityQuery(List.of(), null, "", new double[]{12.0, 64.0, 5.0}, 15.0, 0, 50);
        McpEntityQuery.Page page = around.apply(all);
        assertEquals(List.of(1, -1000, 3), ids(page));
        assertEquals(2.0, page.distances().get(0), 1.0E-9);
        assertTrue(page.distances().get(1) > page.distances().get(0));
        // Without a centre: by network id.
        assertEquals(List.of(-1000, 1, 2, 3, 10, 11), ids(query(List.of(), null, "").apply(all)));
    }

    @Test
    void pagesWalkThroughTheWholeList() {
        world.extraZombies = 230;
        List<McpEntity> all = world.entities();
        List<Integer> seen = new ArrayList<>();
        int offset = 0;
        int pages = 0;
        while (offset >= 0) {
            McpEntityQuery.Page page = new McpEntityQuery(List.of("zombie"), null, "", null, 0.0, offset, 1000).apply(all);
            assertEquals(231, page.total());
            assertTrue(page.entities().size() <= McpEntityQuery.MAX_LIMIT);
            seen.addAll(ids(page));
            offset = page.nextOffset();
            pages++;
        }
        assertEquals(2, pages);
        assertEquals(231, seen.size());
        assertEquals(231, seen.stream().distinct().count());
        McpEntityQuery.Page past = new McpEntityQuery(List.of(), null, "", null, 0.0, 5000, 10).apply(all);
        assertEquals(0, past.entities().size());
        assertEquals(-1, past.nextOffset());
        assertEquals(0, new McpEntityQuery(List.of(), null, "", null, 0.0, -3, 0).offset());
        assertEquals(1, new McpEntityQuery(List.of(), null, "", null, 0.0, 0, 0).limit());
    }

    @Test
    void motionComesFromTheTickBefore() {
        world.cursor = 99;
        List<McpEntity> before = world.entities();
        world.cursor = 100;
        List<McpEntity> now = world.entities();
        McpEntityQuery.motionFrom(before, now);
        McpEntity pig = McpObserveTools.find(now, FakeObserver.PIG_UUID);
        assertEquals(0.1, pig.vx, 1.0E-9);
        assertEquals(2.0, pig.speed(), 1.0E-9);
        McpEntity villager = McpObserveTools.find(now, "2");
        assertEquals(0.0, villager.speed(), 1.0E-9);
        // Nothing to compare with: the game's own motion stays.
        List<McpEntity> alone = world.entities();
        McpEntityQuery.motionFrom(null, alone);
        assertEquals(0.5, McpObserveTools.find(alone, "1").vx, 1.0E-9);
    }

    @Test
    void anEntityAnswersToItsRefUuidIdAndActorId() {
        List<McpEntity> all = world.entities();
        McpEntity alex = McpObserveTools.find(all, "recorder");
        assertEquals(3, alex.id);
        assertEquals("recorder", alex.ref());
        assertTrue(alex.matches(FakeObserver.RECORDER_UUID.toUpperCase()));
        assertTrue(alex.matches("3"));
        McpEntity steve = McpObserveTools.find(all, "actor:actor-1");
        assertEquals(-1000, steve.id);
        assertTrue(steve.matches("actor-1"));
        assertEquals("actor:actor-1", steve.ref());
        assertEquals(FakeObserver.PIG_UUID, McpObserveTools.find(all, "1").ref());
        assertFalse(steve.matches(""));
        assertFalse(steve.matches(null));
    }

    @Test
    void jsonIsBounded() {
        world.extraZombies = 1;
        world.longName = "Z".repeat(300);
        McpEntity zombie = McpObserveTools.find(world.entities(), "100");
        JsonObject summary = zombie.toJson(false);
        assertEquals(McpEntity.MAX_TEXT, summary.get("name").getAsString().length());
        assertEquals(McpEntity.MAX_ITEMS, summary.getAsJsonArray("equipment").size());
        assertFalse(summary.has("size"));
        assertFalse(summary.getAsJsonArray("equipment").get(0).getAsJsonObject().has("name"));
        JsonObject full = zombie.toJson(true);
        assertTrue(full.has("size"));
        assertEquals(McpEntity.MAX_TEXT, full.get("customName").getAsString().length());
        assertEquals(64, full.getAsJsonArray("equipment").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(65.62, full.getAsJsonObject("eye").get("y").getAsDouble(), 1.0E-9);
    }
}
