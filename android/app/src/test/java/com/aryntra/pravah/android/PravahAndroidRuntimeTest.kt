package com.aryntra.pravah.android

import com.aryntra.pravah.core.LifecycleState
import com.aryntra.pravah.core.PravahConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PravahAndroidRuntimeTest {

    private lateinit var runtime: PravahAndroidRuntime

    @BeforeEach
    fun setUp() {
        runtime = PravahAndroidRuntime(PravahConfig("AndroidTestApp", "test", 1025))
    }

    @Test
    fun testRuntimeCreation() {
        assertEquals(LifecycleState.INITIALIZED, runtime.state)
        assertFalse(runtime.isRunning)
    }

    @Test
    fun testRuntimeStartup() {
        runtime.start()
        assertEquals(LifecycleState.RUNNING, runtime.state)
        assertTrue(runtime.isRunning)
    }

    @Test
    fun testRuntimeShutdown() {
        runtime.start()
        runtime.stop()
        assertEquals(LifecycleState.STOPPED, runtime.state)
        assertFalse(runtime.isRunning)
    }

    @Test
    fun testRepeatedLifecycle() {
        // Start -> Stop -> Start should transition cleanly
        runtime.start()
        assertEquals(LifecycleState.RUNNING, runtime.state)
        
        runtime.stop()
        assertEquals(LifecycleState.STOPPED, runtime.state)
        
        runtime.start()
        assertEquals(LifecycleState.RUNNING, runtime.state)
        assertTrue(runtime.isRunning)

        runtime.stop()
        assertEquals(LifecycleState.STOPPED, runtime.state)
    }
}
