package com.aryntra.pravah.android

import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.connectivity.PeerConnectivity
import com.aryntra.pravah.peer.PeerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("A.D2.5 - Diagnostic Transport Drop Isolation & Deactivation Tests")
class DiagnosticTransportDropTest {

    private val peerId = PeerId.of("peer-node-1")

    private fun createActivePath(id: String, transport: String): ConnectivityPath {
        val endpoint = if (transport == "tcp") {
            EndpointAddress.tcp("192.168.1.100", 50001)
        } else {
            EndpointAddress.of("bluetooth", "00:11:22:33:44:55", 1)
        }
        return ConnectivityPath.active(PathId.of(id), peerId, transport, endpoint, "$transport-conn-1")
    }

    @Test
    @DisplayName("DROP TCP deactivates only TCP paths while preserving Bluetooth paths")
    fun testDropTcpDeactivatesTcpPathPreservingBluetooth() {
        val conn = PeerConnectivity(peerId)

        val tcpPath = createActivePath("path-tcp-1", "tcp")
        val btPath = createActivePath("path-bt-1", "bluetooth")

        conn.addPath(tcpPath)
        conn.addPath(btPath)

        assertEquals(2, conn.activePaths().size)

        // Simulate transport drop for tcp
        for (path in conn.activePaths()) {
            if (path.transportName().equals("tcp", ignoreCase = true)) {
                conn.addPath(path.deactivate())
            }
        }

        // Verify TCP is inactive and Bluetooth remains active
        val active = conn.activePaths()
        assertEquals(1, active.size)
        assertEquals("bluetooth", active.first().transportName())
        assertFalse(conn.findPath(PathId.of("path-tcp-1")).get().isActive)
        assertTrue(conn.findPath(PathId.of("path-bt-1")).get().isActive)
    }

    @Test
    @DisplayName("DROP BLUETOOTH deactivates only Bluetooth paths while preserving TCP paths")
    fun testDropBluetoothDeactivatesBluetoothPathPreservingTcp() {
        val conn = PeerConnectivity(peerId)

        val tcpPath = createActivePath("path-tcp-1", "tcp")
        val btPath = createActivePath("path-bt-1", "bluetooth")

        conn.addPath(tcpPath)
        conn.addPath(btPath)

        assertEquals(2, conn.activePaths().size)

        // Simulate transport drop for bluetooth
        for (path in conn.activePaths()) {
            if (path.transportName().equals("bluetooth", ignoreCase = true)) {
                conn.addPath(path.deactivate())
            }
        }

        // Verify Bluetooth is inactive and TCP remains active
        val active = conn.activePaths()
        assertEquals(1, active.size)
        assertEquals("tcp", active.first().transportName())
        assertTrue(conn.findPath(PathId.of("path-tcp-1")).get().isActive)
        assertFalse(conn.findPath(PathId.of("path-bt-1")).get().isActive)
    }

    @Test
    @DisplayName("DROP targeting unestablished transport is a safe no-op on active paths")
    fun testDropTargetNotFoundDoesNotAffectOtherPaths() {
        val conn = PeerConnectivity(peerId)

        val tcpPath = createActivePath("path-tcp-1", "tcp")
        conn.addPath(tcpPath)

        // Attempt drop of non-existent Bluetooth path
        var dropped = false
        for (path in conn.activePaths()) {
            if (path.transportName().equals("bluetooth", ignoreCase = true)) {
                conn.addPath(path.deactivate())
                dropped = true
            }
        }

        assertFalse(dropped)
        assertEquals(1, conn.activePaths().size)
        assertTrue(conn.activePaths().first().isActive)
    }
}