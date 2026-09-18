package com.aryntra.pravah.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core runtime managing application lifecycle, state transitions, and clean shutdown.
 */
public class PravahRuntime {

    private static final Logger LOGGER = Logger.getLogger(PravahRuntime.class.getName());

    private final PravahConfig config;
    private final AtomicReference<LifecycleState> state;

    public PravahRuntime(PravahConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.state = new AtomicReference<>(LifecycleState.INITIALIZED);
    }

    public synchronized void start() {
        if (state.get() != LifecycleState.INITIALIZED && state.get() != LifecycleState.STOPPED) {
            throw new PravahException("Cannot start runtime in state: " + state.get());
        }

        try {
            transitionTo(LifecycleState.STARTING);
            LOGGER.info(() -> "Pravah starting [" + config.appName() + " | env=" + config.environment() + "]");

            // Core initialization hook point (transports/listeners will hook here in S1)

            transitionTo(LifecycleState.RUNNING);
            LOGGER.info("Pravah running");
        } catch (Exception e) {
            transitionTo(LifecycleState.FAILED);
            LOGGER.log(Level.SEVERE, "Failed during startup: " + e.getMessage(), e);
            throw new PravahException("Startup failed", e);
        }
    }

    public synchronized void stop() {
        LifecycleState current = state.get();
        if (current == LifecycleState.STOPPED || current == LifecycleState.STOPPING) {
            return;
        }

        try {
            transitionTo(LifecycleState.STOPPING);
            LOGGER.info("Pravah stopping");

            // Clean resource teardown hook point

            transitionTo(LifecycleState.STOPPED);
            LOGGER.info("Pravah stopped");
        } catch (Exception e) {
            transitionTo(LifecycleState.FAILED);
            LOGGER.log(Level.SEVERE, "Failed during shutdown: " + e.getMessage(), e);
            throw new PravahException("Shutdown failed", e);
        }
    }

    public LifecycleState getState() {
        return state.get();
    }

    public PravahConfig getConfig() {
        return config;
    }

    private void transitionTo(LifecycleState newState) {
        this.state.set(newState);
    }
}