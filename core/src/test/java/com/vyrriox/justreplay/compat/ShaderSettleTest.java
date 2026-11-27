package com.vyrriox.justreplay.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderSettleTest {
    @AfterEach
    void clear() {
        ShaderSettle.reset();
    }

    @Test
    void nothingSettlesBeforeAReload() {
        assertFalse(ShaderSettle.settling(0L));
        assertFalse(ShaderSettle.settling(Long.MAX_VALUE));
    }

    @Test
    void settlesForTheWindowAfterAReload() {
        long t = 5_000_000_000L;
        ShaderSettle.reloaded(t);
        assertTrue(ShaderSettle.settling(t));
        assertTrue(ShaderSettle.settling(t + ShaderSettle.WINDOW_NANOS - 1));
        assertFalse(ShaderSettle.settling(t + ShaderSettle.WINDOW_NANOS));
    }

    @Test
    void aSecondReloadStartsAgain() {
        ShaderSettle.reloaded(0L);
        ShaderSettle.reloaded(2_000_000_000L);
        assertTrue(ShaderSettle.settling(4_000_000_000L));
    }

    @Test
    void wrapsAroundTheNanoClock() {
        long t = Long.MAX_VALUE - 1_000_000_000L;
        ShaderSettle.reloaded(t);
        assertTrue(ShaderSettle.settling(t + 2_000_000_000L));
        assertFalse(ShaderSettle.settling(t + ShaderSettle.WINDOW_NANOS + 1));
    }
}
