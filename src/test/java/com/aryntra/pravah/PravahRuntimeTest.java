package com.aryntra.pravah;

import com.aryntra.pravah.core.LifecycleState;
import com.aryntra.pravah.core.PravahConfig;
import com.aryntra.pravah.core.PravahException;
import com.aryntra.pravah.core.PravahRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PravahRuntimeTest {

    @Test
    @DisplayName("Runtime initializes to INITIALIZED state")
    void testInitialState() {
        PravahRuntime runtime = new PravahRuntime(PravahConfig.defaultConfig());
        assertEquals(LifecycleState.INITIALIZED, runtime.getState());
    }

    @Test
    @DisplayName("Runtime transitions correctly through full lifecycle")
    void testLifecycleTransitions() {
        PravahRuntime runtime = new PravahRuntime(PravahConfig.defaultConfig());

        runtime.start();
        assertEquals(LifecycleState.RUNNING, runtime.getState());

        runtime.stop();
        assertEquals(LifecycleState.STOPPED, runtime.getState());
    }

    @Test
    @DisplayName("Starting an already running runtime throws PravahException")
    void testDoubleStartThrowsException() {
        PravahRuntime runtime = new PravahRuntime(PravahConfig.defaultConfig());
        runtime.start();

        assertThrows(PravahException.class, runtime::start);
        runtime.stop();
    }

    @Test
    @DisplayName("Invalid port in configuration throws IllegalArgumentException")
    void testInvalidPortConfig() {
        assertThrows(IllegalArgumentException.class, () -> new PravahConfig("app", "test", 80));
        assertThrows(IllegalArgumentException.class, () -> new PravahConfig("app", "test", 70000));
    }
}