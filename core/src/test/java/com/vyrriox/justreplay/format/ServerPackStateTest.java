package com.vyrriox.justreplay.format;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resource packs of a server as pushes and removals change them, and what playback must do to reach a new set.
 *
 * @author vyrriox
 */
class ServerPackStateTest {
    private static final String HASH_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String HASH_B = "89ABCDEF0123456789ABCDEF0123456789ABCDEF";

    private static ServerPackState.Pack pack(int id, String hash) {
        return new ServerPackState.Pack(new UUID(0, id), "https://example.org/" + id + ".zip", hash, false);
    }

    private static List<Long> ids(List<ServerPackState.Pack> packs) {
        return packs.stream().map(p -> p.id().getLeastSignificantBits()).toList();
    }

    @Test
    void pushesKeepTheirOrderAndAPushAgainGoesOnTop() {
        ServerPackState state = new ServerPackState();
        state.push(pack(1, HASH_A));
        state.push(pack(2, HASH_A));
        state.push(pack(3, HASH_A));
        assertEquals(List.of(1L, 2L, 3L), ids(state.packs()));
        state.push(pack(1, HASH_B));
        assertEquals(List.of(2L, 3L, 1L), ids(state.packs()));
        assertEquals(HASH_B, state.packs().get(2).hash());
    }

    @Test
    void popRemovesOneOrAll() {
        ServerPackState state = new ServerPackState();
        state.push(pack(1, HASH_A));
        state.push(pack(2, HASH_A));
        state.pop(new UUID(0, 1));
        assertEquals(List.of(2L), ids(state.packs()));
        state.pop(new UUID(0, 9));
        assertEquals(List.of(2L), ids(state.packs()));
        state.pop(null);
        assertTrue(state.isEmpty());
    }

    @Test
    void singlePackVersionsReplaceThePack() {
        ServerPackState state = new ServerPackState();
        state.replaceAll(pack(1, HASH_A));
        state.replaceAll(pack(2, HASH_A));
        assertEquals(List.of(2L), ids(state.packs()));
        state.push(null);
        assertEquals(List.of(2L), ids(state.packs()));
    }

    @Test
    void diffRemovesThenAdds() {
        List<ServerPackState.Pack> applied = List.of(pack(1, HASH_A), pack(2, HASH_A));
        List<ServerPackState.Pack> wanted = List.of(pack(2, HASH_A), pack(3, HASH_A));
        ServerPackState.Diff diff = ServerPackState.diff(applied, wanted);
        assertEquals(List.of(1L), ids(diff.remove()));
        assertEquals(List.of(3L), ids(diff.add()));
        assertFalse(diff.isEmpty());
    }

    @Test
    void sameSetAfterASeekChangesNothing() {
        List<ServerPackState.Pack> set = List.of(pack(1, HASH_A), pack(2, ""));
        assertTrue(ServerPackState.diff(set, List.of(pack(1, HASH_A.toUpperCase()), pack(2, ""))).isEmpty());
        // Required or not, it is the same file on screen.
        ServerPackState.Pack required = new ServerPackState.Pack(new UUID(0, 1), "https://example.org/1.zip", HASH_A, true);
        assertTrue(ServerPackState.diff(List.of(pack(1, HASH_A)), List.of(required)).isEmpty());
    }

    @Test
    void newFileForAnIdIsRemovedThenAdded() {
        ServerPackState.Diff diff = ServerPackState.diff(List.of(pack(1, HASH_A)), List.of(pack(1, HASH_B)));
        assertEquals(List.of(1L), ids(diff.remove()));
        assertEquals(List.of(1L), ids(diff.add()));
        assertEquals(HASH_B, diff.add().get(0).hash());
    }

    @Test
    void everythingGoesWhenNothingIsWanted() {
        ServerPackState.Diff diff = ServerPackState.diff(List.of(pack(1, HASH_A), pack(2, HASH_A)), List.of());
        assertEquals(List.of(1L, 2L), ids(diff.remove()));
        assertTrue(diff.add().isEmpty());
        assertTrue(ServerPackState.diff(List.of(), List.of()).isEmpty());
    }

    @Test
    void cacheNamesAreLowerCaseSha1() {
        assertEquals(HASH_A, ServerPackState.cacheName(HASH_A));
        assertEquals(HASH_B.toLowerCase(), ServerPackState.cacheName(HASH_B));
        assertNull(ServerPackState.cacheName(""));
        assertNull(ServerPackState.cacheName(null));
        assertNull(ServerPackState.cacheName(HASH_A.substring(1)));
        assertNull(ServerPackState.cacheName(HASH_A.substring(1) + "g"));
        assertTrue(ServerPackState.isSha1(HASH_B));
    }

    @Test
    void missingTextBecomesEmpty() {
        ServerPackState.Pack odd = new ServerPackState.Pack(new UUID(0, 1), null, null, false);
        assertEquals("", odd.url());
        assertEquals("", odd.hash());
        assertEquals("abc", new ServerPackState.Pack(new UUID(0, 1), "u", "  abc ", false).hash());
    }

    @Test
    void singlePackIdsFollowTheAddress() {
        assertEquals(ServerPackState.idOf("https://example.org/a.zip"), ServerPackState.idOf("https://example.org/a.zip"));
        assertFalse(ServerPackState.idOf("https://example.org/a.zip").equals(ServerPackState.idOf("https://example.org/b.zip")));
        assertEquals(ServerPackState.idOf(""), ServerPackState.idOf(null));
    }
}
