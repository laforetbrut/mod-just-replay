package com.vyrriox.justreplay.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * The access token of the MCP server: made from 32 random bytes, checked in constant time so that the time of an
 * answer says nothing about how much of a guess was right.
 *
 * @author vyrriox
 */
public final class McpToken {
    /** Shortest token accepted; a generated one has 43 characters. */
    public static final int MIN_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private McpToken() {
    }

    /** A new random token of 43 characters (letters, digits, {@code -} and {@code _}). */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Long enough and made of characters a header can carry as they are (no spaces, no quotes). */
    public static boolean usable(String token) {
        if (token == null || token.length() < MIN_LENGTH || token.length() > 512) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Whether an {@code Authorization} header value is {@code Bearer <expected>}. */
    public static boolean matches(String expected, String authorization) {
        if (expected == null || expected.isEmpty() || authorization == null) {
            return false;
        }
        String value = authorization.trim();
        int space = value.indexOf(' ');
        if (space < 0 || !value.substring(0, space).toLowerCase(Locale.ROOT).equals("bearer")) {
            return false;
        }
        byte[] given = value.substring(space + 1).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given);
    }
}
