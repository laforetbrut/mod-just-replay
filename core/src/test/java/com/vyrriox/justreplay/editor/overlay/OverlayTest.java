package com.vyrriox.justreplay.editor.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Formatting codes of overlay texts: stripped like the game's {@code ChatFormatting.stripFormatting}, so the overlay
 * list shows the plain first line.
 *
 * @author vyrriox
 */
class OverlayTest {
    @Test
    void formattingCodesAreStrippedInAnyCase() {
        assertNull(Overlay.stripFormatting(null));
        assertEquals("", Overlay.stripFormatting(""));
        assertEquals("Gold Bold end", Overlay.stripFormatting("\u00a76Gold \u00a7lBold\u00a7r end"));
        assertEquals("Hi", Overlay.stripFormatting("\u00a7AHi"));
        assertEquals("Hi", Overlay.stripFormatting("\u00a7KH\u00a7Oi"));
    }

    @Test
    void anythingElseStays() {
        // Not a code letter, a lone section sign, an ampersand code (turned into a section sign by lines() only),
        // and letters that only match under Unicode case folding (the game's pattern has no UNICODE_CASE).
        assertEquals("\u00a7zHi", Overlay.stripFormatting("\u00a7zHi"));
        assertEquals("\u00a7pHi", Overlay.stripFormatting("\u00a7pHi"));
        assertEquals("end\u00a7", Overlay.stripFormatting("end\u00a7"));
        assertEquals("&aHi", Overlay.stripFormatting("&aHi"));
        assertEquals("\u00a7\u212a", Overlay.stripFormatting("\u00a7\u212a"));
        assertEquals("\u00a7", Overlay.stripFormatting("\u00a7\u00a7a"));
    }

    @Test
    void theListShowsThePlainFirstLine() {
        Overlay overlay = new Overlay();
        overlay.name = "";
        overlay.text = "&6&lTitle&r here\\nsecond line";
        assertEquals("Title here", overlay.displayName());
        overlay.text = "&a";
        assertEquals("Text", overlay.displayName());
        overlay.name = "Intro";
        assertEquals("Intro", overlay.displayName());
    }
}
