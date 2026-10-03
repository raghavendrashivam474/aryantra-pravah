package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.peer.PeerId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * JUnit 5 unit tests verifying Diagnostic UI presentation mapping (§23).
 */
class DiagnosticModelMapperTest {

    @Test
    fun testIdleStateMapping() {
        val testPeerId = PeerId.of("android-test-node")
        val manager = PravahAndroidMessagingManager(testPeerId)

        val state = DiagnosticModelMapper.map(manager, null)

        assertNotNull(state)
        assertEquals("android-test-node", state.nodeStatus.localPeerId)
        assertFalse(state.nodeStatus.isRunning)
        assertFalse(state.nodeStatus.discoveryActive)
        assertEquals("-", state.nodeStatus.tcpPortStr)
        assertEquals("NONE", state.nodeStatus.connectedPeerId)
        assertEquals("-", state.nodeStatus.sessionState)

        // Operations Panel state checks (§13)
        assertTrue(state.operations.startEnabled)
        assertFalse(state.operations.stopEnabled)
        assertFalse(state.operations.discoveryEnabled)
        assertFalse(state.operations.connectTcpEnabled)
        assertFalse(state.operations.connectBtEnabled)
        assertFalse(state.operations.simulateDropEnabled)
        assertFalse(state.operations.sendEnabled)

        // Snapshot verification (§9)
        assertEquals(0, state.snapshot.peersCount)
        assertEquals(0, state.snapshot.activePathsCount)
    }

    @Test
    fun testConnectedPeerContextMapping() {
        val testPeerId = PeerId.of("android-test-node")
        val targetPeerId = PeerId.of("android-remote-peer")
        val manager = PravahAndroidMessagingManager(testPeerId)

        val state = DiagnosticModelMapper.map(manager, targetPeerId)

        assertNotNull(state)
        assertEquals("android-test-node", state.nodeStatus.localPeerId)
        assertEquals("android-remote-peer", state.nodeStatus.connectedPeerId)
        assertEquals("JOINED", state.nodeStatus.sessionState)
    }
}
