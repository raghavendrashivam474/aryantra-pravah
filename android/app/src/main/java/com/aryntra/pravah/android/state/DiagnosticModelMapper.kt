package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState

/**
 * A.D2.1: Stabilized mapper.
 * Fixes:
 * - Issue E: Dispatch route semantics (clearly distinguishes active route vs NONE)
 * - Issue F: Preserves 16-char PeerId labels for distinguishable peer identity
 * - Issue C: Sorts paths deterministically (ACTIVE paths first, then transport type)
 */
object DiagnosticModelMapper {

    fun map(
        manager: PravahAndroidMessagingManager,
        connectedPeerId: PeerId?,
        liveEvents: List<LiveWireEvent> = emptyList()
    ): DiagnosticState {
        val isRunning = manager.isRunning
        val boundPort = if (isRunning) manager.boundPort.toString() else "-"
        val isDiscovering = manager.isDiscovering
        val localPeerIdVal = manager.localPeerId.value()

        val peerStr = connectedPeerId?.value() ?: "NONE"
        val sessionStr = connectedPeerId?.let {
            manager.getSessionState(it.value())?.name ?: "UNKNOWN"
        } ?: "NONE"

        val nodeStatus = NodeStatusState(
            isRunning = isRunning,
            localPeerId = localPeerIdVal,
            tcpPortStr = boundPort,
            discoveryActive = isDiscovering,
            activeDiscoveryPort = if (isDiscovering) manager.discoveryPort else 0,
            connectedPeerId = peerStr,
            sessionState = sessionStr
        )

        val allConnectivities = manager.connectivityRegistry.allConnectivities()
        val peersList = mutableListOf<PeerItemState>()
        val pathsList = mutableListOf<PathItemState>()
        var activePathsCount = 0

        val topologyPeers = mutableListOf<TopologyNode>()
        val topologyEdges = mutableListOf<TopologyEdge>()

        for (conn in allConnectivities) {
            val peerIdVal = conn.peerId().value()

            // Issue E: Resolve active dispatch route from router
            val resolvedRoute = try {
                val selected = manager.router.resolveConnectionId(conn.peerId())
                if (selected != null && selected.isNotEmpty()) selected else "NONE"
            } catch (_: Exception) {
                "NONE"
            }

            peersList.add(
                PeerItemState(
                    peerId = peerIdVal,
                    sessionState = manager.getSessionState(peerIdVal)?.name ?: "UNKNOWN",
                    resolvedRoute = resolvedRoute
                )
            )

            // Issue F: 16-char readable identifier
            topologyPeers.add(
                TopologyNode(
                    id = peerIdVal,
                    label = if (peerIdVal.length > 16) peerIdVal.take(16) + ".." else peerIdVal
                )
            )

            // Issue C: Sort paths so ACTIVE appears before INACTIVE
            val sortedPaths = conn.allPaths().sortedWith(
                compareByDescending<ConnectivityPath> { it.isActive }
                    .thenBy { it.transportName() }
            )

            for (path in sortedPaths) {
                val transportName = path.transportName()
                val transportType = when {
                    transportName.equals("tcp", ignoreCase = true) -> "TCP"
                    transportName.contains("bt", ignoreCase = true) ||
                    transportName.contains("bluetooth", ignoreCase = true) -> "BLUETOOTH"
                    else -> transportName.uppercase()
                }

                val pathStateName = try {
                    path.state().name
                } catch (_: Exception) {
                    if (path.isActive) "ACTIVE" else "INACTIVE"
                }

                val isActive = path.isActive
                if (isActive) activePathsCount++

                // Determine if this path is the active dispatch route
                val isSelected = try {
                    val selectedConnId = manager.router.resolveConnectionId(conn.peerId())
                    val thisConnId = path.optionalConnectionId().orElse("")
                    isActive &&
                    selectedConnId != null &&
                    thisConnId.isNotEmpty() &&
                    selectedConnId == thisConnId
                } catch (_: Exception) {
                    false
                }

                val connIdDisplay = if (transportType == "BLUETOOTH") {
                    path.optionalConnectionId().orElse("no-conn")
                } else {
                    val rawConn = path.optionalConnectionId().orElse("no-conn")
                    if (rawConn.startsWith("bt:")) "no-conn"
                    else if (rawConn.startsWith("/")) rawConn.substring(1)
                    else rawConn
                }

                pathsList.add(
                    PathItemState(
                        peerId = peerIdVal,
                        transportType = transportType,
                        isActive = isActive,
                        connectionId = connIdDisplay,
                        pathState = pathStateName,
                        isSelected = isSelected
                    )
                )

                topologyEdges.add(
                    TopologyEdge(
                        fromNodeId = "LOCAL",
                        toNodeId = peerIdVal,
                        transportType = transportType,
                        pathState = pathStateName,
                        isSelected = isSelected
                    )
                )
            }
        }

        val snapshot = NetworkSnapshotState(
            peersCount = allConnectivities.size,
            activePathsCount = activePathsCount
        )

        val startEnabled = !isRunning
        val stopEnabled = isRunning
        val discoveryEnabled = isRunning
        val discoveryText = if (isDiscovering) "STOP DISC" else "DISCOVER"
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

        val topology = TopologyState(
            localNode = TopologyNode(
                id = "LOCAL",
                label = if (localPeerIdVal.length > 16) localPeerIdVal.take(16) + ".." else localPeerIdVal
            ),
            peers = topologyPeers,
            edges = topologyEdges
        )

        val bufferState = try {
            val tb = manager.router.transitionBuffer()
            if (tb != null) {
                TransitionBufferState(
                    currentSize = tb.size(),
                    currentBytes = tb.currentBytes().toLong(),
                    totalBuffered = tb.totalBuffered(),
                    totalFlushed = tb.totalFlushed(),
                    totalExpired = tb.totalExpired(),
                    totalEvicted = tb.totalEvicted(),
                    totalRejected = tb.totalRejected()
                )
            } else {
                TransitionBufferState()
            }
        } catch (_: Exception) {
            TransitionBufferState()
        }

        return DiagnosticState(
            nodeStatus = nodeStatus,
            snapshot = snapshot,
            peers = peersList,
            paths = pathsList,
            operations = operations,
            liveWireEvents = liveEvents,
            topology = topology,
            bufferState = bufferState
        )
    }
}
