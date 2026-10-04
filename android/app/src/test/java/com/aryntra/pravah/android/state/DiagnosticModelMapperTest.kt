package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.android.presentation.NetworkSnapshotPanel
import com.aryntra.pravah.android.presentation.PathPanel
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.transport.Transport
import com.aryntra.pravah.transport.TransportListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A.D2.2 Test Suite — Extended regression test coverage (§24).
 * Covers stopped state, topology, PathState mapping, route resolution,
 * duplicate path handling, dispatch route fallback, and stale path cleanup.
 */
class DiagnosticModelMapperTest {

    private lateinit var manager: PravahAndroidMessagingManager
    private val localPeerId = PeerId.of("android-test-local")
    private val remotePeerId = PeerId.of("android-test-remote")

    private val dummyTransport = object : Transport {
        var running: Boolean = false
        override fun getName(): String = "dummy"
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun isRunning(): Boolean = running
        override fun send(destinationId: String, payload: ByteArray) {}
        override fun setListener(listener: TransportListener?) {}
    }

    @BeforeEach
    fun setUp() {
        dummyTransport.running = false
        manager = PravahAndroidMessagingManager(
            localPeerId = localPeerId,
            customTransport = dummyTransport
        )
    }

    @Test
    fun testInitialStoppedStateMapping() {
        val state = DiagnosticModelMapper.map(manager, null)

        assertNotNull(state)
        assertEquals("android-test-local", state.nodeStatus.localPeerId)
        assertEquals("NONE", state.nodeStatus.connectedPeerId)
        assertEquals(0, state.snapshot.peersCount)
        assertEquals(0, state.snapshot.activePathsCount)
        assertEquals(0, state.peers.size)
        assertEquals(0, state.paths.size)
        assertTrue(state.operations.startEnabled)
        assertFalse(state.operations.stopEnabled)
        assertFalse(state.operations.sendEnabled)

        assertNotNull(state.topology)
        assertEquals("LOCAL", state.topology.localNode.id)
        assertEquals(0, state.topology.peers.size)
        assertEquals(0, state.topology.edges.size)
        assertEquals(0L, state.bufferState.totalBuffered)
    }

    @Test
    fun testLiveTopologyAndPathStateMapping() {
        val tcpPathId = PathId.of("tcp-p1")
        val tcpEndpoint = EndpointAddress.tcp("192.168.1.100", 50001)
        val activeTcp = ConnectivityPath.active(tcpPathId, remotePeerId, "tcp", tcpEndpoint, "tcp-conn-101")
        manager.connectivityRegistry.registerPath(remotePeerId, activeTcp)

        val btPathId = PathId.of("bt-p2")
        val btEndpoint = EndpointAddress.of("bluetooth", "00:11:22:33:44:55", 1)
        val candidateBt = ConnectivityPath.candidate(btPathId, remotePeerId, "bluetooth", btEndpoint)
        manager.connectivityRegistry.registerPath(remotePeerId, candidateBt)

        val state = DiagnosticModelMapper.map(manager, remotePeerId)

        assertEquals(1, state.snapshot.peersCount)
        assertEquals(1, state.snapshot.activePathsCount)

        assertEquals(1, state.peers.size)
        assertEquals("android-test-remote", state.peers[0].peerId)

        assertEquals(2, state.paths.size)
        val tcpMapped = state.paths.first { it.transportType == "TCP" }
        val btMapped = state.paths.first { it.transportType == "BLUETOOTH" }

        assertEquals("ACTIVE", tcpMapped.pathState)
        assertTrue(tcpMapped.isActive)
        assertEquals("tcp-conn-101", tcpMapped.connectionId)

        assertEquals("CANDIDATE", btMapped.pathState)
        assertFalse(btMapped.isActive)

        assertEquals(1, state.topology.peers.size)
        assertEquals(2, state.topology.edges.size)

        val tcpEdge = state.topology.edges.first { it.transportType == "TCP" }
        val btEdge = state.topology.edges.first { it.transportType == "BLUETOOTH" }

        assertEquals("ACTIVE", tcpEdge.pathState)
        assertEquals("CANDIDATE", btEdge.pathState)
    }

    @Test
    fun testSelectedRouteIndication() {
        val tcpPathId = PathId.of("tcp-p1")
        val tcpEndpoint = EndpointAddress.tcp("192.168.1.100", 50001)
        val activeTcp = ConnectivityPath.active(tcpPathId, remotePeerId, "tcp", tcpEndpoint, "tcp-conn-101")
        manager.connectivityRegistry.registerPath(remotePeerId, activeTcp)

        val state = DiagnosticModelMapper.map(manager, remotePeerId)
        val tcpMapped = state.paths.first { it.transportType == "TCP" }

        assertTrue(tcpMapped.isSelected)
        assertTrue(state.topology.edges.first { it.transportType == "TCP" }.isSelected)
    }

    @Test
    fun testDispatchRouteFallbackWhenNoActivePath() {
        val tcpPathId = PathId.of("tcp-p1")
        val tcpEndpoint = EndpointAddress.tcp("192.168.1.100", 50001)
        val inactiveTcp = ConnectivityPath.inactive(tcpPathId, remotePeerId, "tcp", tcpEndpoint)
        manager.connectivityRegistry.registerPath(remotePeerId, inactiveTcp)

        val state = DiagnosticModelMapper.map(manager, remotePeerId)
        assertEquals("NONE", state.peers[0].resolvedRoute)

        val panel = PathPanel()
        val routeStr = panel.formatDispatchRoute(state.peers[0].resolvedRoute)
        assertTrue(routeStr.contains("NONE (No active path)"))
    }

    @Test
    fun testDuplicatePathOrderingActiveFirst() {
        val inactivePathId = PathId.of("tcp-p0")
        val activePathId = PathId.of("tcp-p1")
        val tcpEndpoint = EndpointAddress.tcp("192.168.1.100", 50001)

        val inactiveTcp = ConnectivityPath.inactive(inactivePathId, remotePeerId, "tcp", tcpEndpoint)
        val activeTcp = ConnectivityPath.active(activePathId, remotePeerId, "tcp", tcpEndpoint, "tcp-conn-101")

        manager.connectivityRegistry.registerPath(remotePeerId, inactiveTcp)
        manager.connectivityRegistry.registerPath(remotePeerId, activeTcp)

        val state = DiagnosticModelMapper.map(manager, remotePeerId)
        assertEquals(2, state.paths.size)
        assertEquals("ACTIVE", state.paths[0].pathState)
        assertTrue(state.paths[0].isSelected)
        assertEquals("INACTIVE", state.paths[1].pathState)
        assertFalse(state.paths[1].isSelected)
    }

    @Test
    fun testStalePathRemovalAndCleanup() {
        // Section 24: Verify removePath() cleans up entries properly
        val pathId = PathId.of("tcp-p1")
        val tcpEndpoint = EndpointAddress.tcp("192.168.1.100", 50001)
        val activeTcp = ConnectivityPath.active(pathId, remotePeerId, "tcp", tcpEndpoint, "tcp-conn-101")

        manager.connectivityRegistry.registerPath(remotePeerId, activeTcp)
        val stateBefore = DiagnosticModelMapper.map(manager, remotePeerId)
        assertEquals(1, stateBefore.paths.size)

        // Remove the path
        val connectivity = manager.connectivityRegistry.lookup(remotePeerId).orElseThrow()
        connectivity.removePath(pathId)

        val stateAfter = DiagnosticModelMapper.map(manager, remotePeerId)
        assertEquals(0, stateAfter.paths.size)
        assertEquals(0, stateAfter.snapshot.activePathsCount)
    }

    @Test
    fun testLiveWireEventPassing() {
        val events = listOf(
            LiveWireEvent("10:00:00", "SYSTEM", "SYSTEM", "Node start"),
            LiveWireEvent("10:00:01", "TX", "TX", "SENT: Hello"),
            LiveWireEvent("10:00:02", "RX", "RX", "RECV: World")
        )

        val state = DiagnosticModelMapper.map(manager, remotePeerId, events)
        assertEquals(3, state.liveWireEvents.size)
        assertEquals("10:00:01", state.liveWireEvents[1].timestamp)
        assertEquals("TX", state.liveWireEvents[1].eventType)
        assertEquals("SENT: Hello", state.liveWireEvents[1].detail)
    }

    @Test
    fun testAsciiTopologyRenderer() {
        val panel = NetworkSnapshotPanel()
        val topo = TopologyState(
            localNode = TopologyNode("LOCAL", "android-local"),
            peers = listOf(TopologyNode("remote-1", "peer-b")),
            edges = listOf(
                TopologyEdge("LOCAL", "remote-1", "TCP", "ACTIVE", isSelected = true),
                TopologyEdge("LOCAL", "remote-1", "BLUETOOTH", "CANDIDATE", isSelected = false)
            )
        )

        val ascii = panel.renderAsciiTopology(topo)
        assertNotNull(ascii)
        assertTrue(ascii.contains("LIVE NETWORK TOPOLOGY"))
        assertTrue(ascii.contains("PEER: peer-b"))
        assertTrue(ascii.contains("TCP"))
        assertTrue(ascii.contains("ACTIVE"))
        assertTrue(ascii.contains("SELECTED"))
        assertTrue(ascii.contains("BLUETOOTH"))
        assertTrue(ascii.contains("CANDIDATE"))
    }

    @Test
    fun testPathPanelFormatting() {
        val panel = PathPanel()

        val activePath = PathItemState(
            peerId = "peer-1",
            transportType = "TCP",
            isActive = true,
            connectionId = "192.168.1.50:5000",
            pathState = "ACTIVE",
            isSelected = true
        )
        val formattedActive = panel.formatPath(activePath)
        assertTrue(formattedActive.contains("● ACTIVE"))
        assertTrue(formattedActive.contains("[SELECTED ROUTE]"))

        val candidatePath = PathItemState(
            peerId = "peer-1",
            transportType = "BLUETOOTH",
            isActive = false,
            connectionId = "no-conn",
            pathState = "CANDIDATE",
            isSelected = false
        )
        val formattedCandidate = panel.formatPath(candidatePath)
        assertTrue(formattedCandidate.contains("◐ CANDIDATE"))
        assertFalse(formattedCandidate.contains("[SELECTED ROUTE]"))

        val inactivePath = PathItemState(
            peerId = "peer-1",
            transportType = "TCP",
            isActive = false,
            connectionId = "no-conn",
            pathState = "INACTIVE",
            isSelected = false
        )
        val formattedInactive = panel.formatPath(inactivePath)
        assertTrue(formattedInactive.contains("○ INACTIVE"))
    }
}
