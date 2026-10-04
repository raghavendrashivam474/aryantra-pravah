package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState

/**
 * A.D2: Extended mapper.
 * Preserves all A.D1 mapping logic.
 * Adds: PathState enum reading, selected route flag,
 *       topology construction, TransitionBuffer counters.
 * All new data flows through the existing manager boundary.
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

        // --- A.D1 + A.D2: Iterate connectivity registry ---
        val allConnectivities = manager.connectivityRegistry.allConnectivities()
        val peersList = mutableListOf<PeerItemState>()
        val pathsList = mutableListOf<PathItemState>()
        var activePathsCount = 0

        // A.D2: Topology builders
        val topologyPeers = mutableListOf<TopologyNode>()
        val topologyEdges = mutableListOf<TopologyEdge>()

        for (conn in allConnectivities) {
            val peerIdVal = conn.peerId().value()

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

            // A.D2: Add peer to topology
            topologyPeers.add(
                TopologyNode(
                    id = peerIdVal,
                    label = if (peerIdVal.length > 12) peerIdVal.take(12) + ".." else peerIdVal
                )
            )

            for (path in conn.allPaths()) {
                val transportName = path.transportName()
                val transportType = when {
                    transportName.equals("tcp", ignoreCase = true) -> "TCP"
                    transportName.contains("bt", ignoreCase = true) ||
                    transportName.contains("bluetooth", ignoreCase = true) -> "BLUETOOTH"
                    else -> transportName.uppercase()
                }

                // A.D2: Read real PathState from Core enum (§9)
                val pathStateName = try {
                    path.state().name
                } catch (_: Exception) {
                    if (path.isActive) "ACTIVE" else "INACTIVE"
                }

                val isActive = path.isActive

                if (isActive) activePathsCount++

                // A.D2: Determine if this path is the selected dispatch route (§10)
                val isSelected = try {
                    val selectedConnId = manager.router.resolveConnectionId(conn.peerId())
                    val thisConnId = path.optionalConnectionId().orElse("")
                    selectedConnId != null &&
                    thisConnId.isNotEmpty() &&
                    selectedConnId == thisConnId
                } catch (_: Exception) {
                    false
                }

                val connIdDisplay = if (transportType == "BLUETOOTH") {
                    val cleanConn = path.optionalConnectionId().orElse("no-conn")
                    cleanConn
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

                // A.D2: Add edge to topology (§8-9)
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

        // --- Operations (A.D1 preserved) ---
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

        // --- A.D2: Topology assembly (§8-9) ---
        val topology = TopologyState(
            localNode = TopologyNode(
                id = "LOCAL",
                label = if (localPeerIdVal.length > 12) localPeerIdVal.take(12) + ".." else localPeerIdVal
            ),
            peers = topologyPeers,
            edges = topologyEdges
        )

        // --- A.D2: B.R2 TransitionBuffer observability (§18) ---
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
