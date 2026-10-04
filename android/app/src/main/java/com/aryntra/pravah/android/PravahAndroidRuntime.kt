package com.aryntra.pravah.android

import com.aryntra.pravah.core.LifecycleState
import com.aryntra.pravah.core.PravahConfig
import com.aryntra.pravah.core.PravahRuntime
import java.util.logging.Logger

/**
 * Android runtime adapter hosting the Pravah communication system.
 * Maps Android-specific concerns and handles clear lifecycle boundaries.
 */
class PravahAndroidRuntime(val config: PravahConfig = PravahConfig.defaultConfig()) {
    private val logger = Logger.getLogger(PravahAndroidRuntime::class.java.name)
    
    // Core runtime reference
    private var coreRuntime = PravahRuntime(config)

    /**
     * Safe startup of the underlying Pravah engine.
     * Recreates the core runtime if it was previously stopped to avoid lifecycle corruption.
     */
    @Synchronized
    fun start() {
        val currentState = coreRuntime.state
        logger.info("PravahAndroidRuntime: start requested. Current internal state: $currentState")
        
        if (currentState == LifecycleState.RUNNING || currentState == LifecycleState.STARTING) {
            logger.warning("PravahAndroidRuntime is already active.")
            return
        }

        // If stopped or failed, re-instantiate core runtime to ensure we start clean (Section 11, Test 4 requirement)
        if (currentState == LifecycleState.STOPPED || currentState == LifecycleState.FAILED) {
            logger.info("Re-instantiating core runtime for clean transition.")
            coreRuntime = PravahRuntime(config)
        }

        coreRuntime.start()
    }

    /**
     * Safe clean shutdown of the Pravah engine.
     */
    @Synchronized
    fun stop() {
        logger.info("PravahAndroidRuntime: stop requested.")
        coreRuntime.stop()
    }

    /**
     * Checks if the runtime is currently running.
     */
    val isRunning: Boolean
        get() = coreRuntime.state == LifecycleState.RUNNING

    /**
     * Returns the current core lifecycle state.
     */
    val state: LifecycleState
        get() = coreRuntime.state
}
