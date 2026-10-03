package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState

/**
 * Pure mapping implementation: Pravaah Core & Manager state -> UI-facing DiagnosticState.
 * Adheres to rule §15 (Contracts) and protects underlying core from direct UI manipulation.
 */
object DiagnosticModelMapper {

    fun map(
        manager: PravahAndroidMessagingManager,
        connectedPeerId: PeerId?
    ): DiagnosticState {
        val isRunning = manager.isRunning
        val boundPort = if (isRunning) manager.boundPort.toString() else "-"
        val isDiscovering = manager.isDiscovering
        val localPeerIdVal = manager.localPeerId.value()

        // 1. Resolve connected session status
        val peerStr = connectedPeerId?.value() ?: "NONE"
        val sessionStr = connectedPeerId?.let {
            manager.getSessionState(it.value())?.name ?: PeerState.JOINED.name
        } ?: "-"

        // 2. Map NodeStatus
        val nodeStatus = NodeStatusState(
            isRunning = isRunning,
            localPeerId = localPeerIdVal,
            tcpPortStr = boundPort,
            discoveryActive = isDiscovering,
            activeDiscoveryPort = manager.discoveryPort,
            connectedPeerId = peerStr,
            sessionState = sessionStr
        )

        // 3. Collect core connectivity registries
        val allConnectivities = manager.connectivityRegistry.allConnectivities()
            .filter { it.peerId().value() != "remote-bt-node" || manager.connectivityRegistry.allConnectivities().size == 1 }

        // 4. Map Peers & Paths lists
        val peersList = mutableListOf<PeerItemState>()
        val pathsList = mutableListOf<PathItemState>()
        var activePathsCount = 0

        for (conn in allConnectivities) {
            val peerIdVal = conn.peerId().value()
            val resolvedRoute = try {
                val selected = manager.router.resolveConnectionId(conn.peerId())
                if (selected.startsWith("/")) selected.substring(1) else selected
            } catch (e: Exception) {
                "none"
            }

            peersList.add(
                PeerItemState(
                    peerId = peerIdVal,
                    sessionState = manager.getSessionState(peerIdVal)?.name ?: "JOINED",
                    resolvedRoute = resolvedRoute
                )
            )

            // Parse TCP & BT Path internals directly matching old updateDashboard semantics
            val tcpPath = conn.allPaths().firstOrNull { it.transportName().equals("tcp", ignoreCase = true) }
            val btPath = conn.allPaths().firstOrNull { it.transportName().contains("bt", ignoreCase = true) || it.transportName().contains("bluetooth", ignoreCase = true) }

            if (btPath != null) {
                if (btPath.isActive) activePathsCount++
                val cleanConn = btPath.optionalConnectionId()
                    .map { if (it.startsWith("/")) it.substring(1) else it }
                    .orElse("no-conn")

                pathsList.add(
                    PathItemState(
                        peerId = peerIdVal,
                        transportType = "BLUETOOTH",
                        isActive = btPath.isActive,
                        connectionId = cleanConn
                    )
                )
            }

            if (tcpPath != null) {
                if (tcpPath.isActive) activePathsCount++
                val rawConn = tcpPath.optionalConnectionId().orElse("no-conn")
                val cleanConn = if (rawConn.startsWith("bt:")) "no-conn" else (if (rawConn.startsWith("/")) rawConn.substring(1) else rawConn)

                pathsList.add(
                    PathItemState(
                        peerId = peerIdVal,
                        transportType = "TCP",
                        isActive = tcpPath.isActive,
                        connectionId = cleanConn
                    )
                )
            }
        }

        // 5. Network snapshot summary
        val snapshot = NetworkSnapshotState(
            peersCount = allConnectivities.size,
            activePathsCount = activePathsCount
        )

        // 6. Map OperationsState (controls enable/disable rules matches original activity)
        val startEnabled = !isRunning
        val stopEnabled = isRunning
        val discoveryEnabled = isRunning
        val discoveryText = if (isDiscovering) "STOP DISC" else "DISCOVER"

        // Discoveries list helps determine if connection commands should be enabled
        val hasDiscoveredPeers = manager.discoveredPeers.isNotEmpty()
        val connectTcpEnabled = isRunning && hasDiscoveredPeers
        val connectBtEnabled = isRunning

        val hasSession = connectedPeerId != null
        val simulateDropEnabled = isRunning && hasSession
        val sendEnabled = isRunning && hasSession

        val operations = OperationsState(
            startEnabled = startEnabled,
            stopEnabled = stopEnabled,
            discoveryText = discoveryText,
            discoveryEnabled = discoveryEnabled,
            connectTcpEnabled = connectTcpEnabled,
            connectBtEnabled = connectBtEnabled,
            simulateDropEnabled = simulateDropEnabled,
            sendEnabled = sendEnabled
        )

        return DiagnosticState(
            nodeStatus = nodeStatus,
            snapshot = snapshot,
            peers = peersList,
            paths = pathsList,
            operations = operations
        )
    }
}
