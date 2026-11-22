package com.vyrriox.justreplay.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Access tokens: made random, long enough, and checked against the Authorization header.
 *
 * @author vyrriox
 */
class McpTokenTest {
    @Test
    void generatedTokensAreLongRandomAndUsable() {
        String a = McpToken.generate();
        String b = McpToken.generate();
        assertEquals(43, a.length());
        assertNotEquals(a, b);
        assertTrue(McpToken.usable(a));
        assertTrue(a.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void shortOrOddTokensAreNotUsable() {
        assertFalse(McpToken.usable(null));
        assertFalse(McpToken.usable(""));
        assertFalse(McpToken.usable("short"));
        assertFalse(McpToken.usable("has spaces in the middle of it"));
        assertFalse(McpToken.usable("quote\"quote\"quote\"quote"));
        assertTrue(McpToken.usable("0123456789abcdef"));
    }

    @Test
    void onlyTheBearerTokenMatches() {
        String token = McpToken.generate();
        assertTrue(McpToken.matches(token, "Bearer " + token));
        assertTrue(McpToken.matches(token, "bearer   " + token + "  "));
        assertFalse(McpToken.matches(token, token));
        assertFalse(McpToken.matches(token, "Basic " + token));
        assertFalse(McpToken.matches(token, "Bearer " + token.substring(1)));
        assertFalse(McpToken.matches(token, "Bearer " + token + "x"));
        assertFalse(McpToken.matches(token, null));
        assertFalse(McpToken.matches("", "Bearer "));
    }
}
