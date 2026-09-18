package com.aryntra.pravah;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FoundationSmokeTest {

    @Test
    @DisplayName("S0.1 Smoke Test: Project runtime baseline is verifiable")
    void testFoundationBaseline() {
        // Verifies class loading and entry point execution without unhandled exceptions
        assertDoesNotThrow(() -> Main.main(new String[]{}));
        assertNotNull(Main.class.getClassLoader());
    }
}