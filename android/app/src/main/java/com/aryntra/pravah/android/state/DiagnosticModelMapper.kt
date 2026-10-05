package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.security.trust.TrustState

/**
 * A.D3: Presentation Model Mapper.
 * Maps Core authorities (ConnectivityRegistry, PeerTrustManager, PeerRouter, DeliveryOutbox)
 * into human-first and progressive technical UI states.
 *
 * Preserves the Track A architectural boundary:
 * Core / Managers -> DiagnosticModelMapper -> DiagnosticState -> UI
 */
object DiagnosticModelMapper {

    fun map(
        manager: PravahAndroidMessagingManager,
        connectedPeerId: PeerId?,
        liveEvents: List<LiveWireEvent> = emptyList(),
        uiMessages: List<UiMessageItem> = emptyList()
    ): DiagnosticState {
        val isRunning = manager.isRunning
        val boundPort = if (isRunning) manager.boundPort.toString() else "-"
        val isDiscovering = manager.isDiscovering
        val localPeerIdVal = manager.localPeerId.value()
        val localDisplayName = formatHumanDisplayName(localPeerIdVal)
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
            sessionState = sessionStr,
            localDisplayName = localDisplayName
        )

        val allConnectivities = manager.connectivityRegistry.allConnectivities()
        val peersList = mutableListOf<PeerItemState>()
        val pathsList = mutableListOf<PathItemState>()
        val peerContextsList = mutableListOf<PeerContextState>()
        var activePathsCount = 0
        var trustedPeersCount = 0
        val topologyPeers = mutableListOf<TopologyNode>()
        val topologyEdges = mutableListOf<TopologyEdge>()

        for (conn in allConnectivities) {
            val peerId = conn.peerId()
            val peerIdVal = peerId.value()
            val peerDisplayName = formatHumanDisplayName(peerIdVal)

            // Resolve Trust State from PeerTrustManager (SX.4 Authority)
            val trustState: TrustState = try {
                manager.getTrustState(peerId)
            } catch (_: Exception) {
                TrustState.UNKNOWN
            }
            val isTrusted = trustState == TrustState.TRUSTED
            if (isTrusted) trustedPeersCount++

            // Resolve active dispatch route from router
            val resolvedRoute = if (conn.hasActivePath()) {
                try {
                    val rawSelected = manager.router.resolveConnectionId(conn.peerId())
                    val selected = if (rawSelected != null && rawSelected.startsWith("/")) rawSelected.substring(1) else rawSelected
                    if (selected != null && selected.isNotEmpty()) selected else "NONE"
                } catch (_: Exception) {
                    "NONE"
                }
            } else {
                "NONE"
            }

            peersList.add(
                PeerItemState(
                    peerId = peerIdVal,
                    sessionState = manager.getSessionState(peerIdVal)?.name ?: "UNKNOWN",
                    resolvedRoute = resolvedRoute,
                    displayName = peerDisplayName,
                    trustState = trustState.name,
                    isTrusted = isTrusted
                )
            )

            topologyPeers.add(
                TopologyNode(
                    id = peerIdVal,
                    label = peerDisplayName
                )
            )

            // Sort paths: ACTIVE before INACTIVE, then by transport name
            val sortedPaths = conn.allPaths().sortedWith(
                compareByDescending<ConnectivityPath> { it.isActive }
                    .thenBy { it.transportName() }
            )

            val peerPathsList = mutableListOf<PathItemState>()

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

                val pathItem = PathItemState(
                    peerId = peerIdVal,
                    transportType = transportType,
                    isActive = isActive,
                    connectionId = connIdDisplay,
                    pathState = pathStateName,
                    isSelected = isSelected
                )

                pathsList.add(pathItem)
                peerPathsList.add(pathItem)

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

            // Determine Presence (ONLINE if has active path, REACHABLE if known endpoint, else OFFLINE)
            val presenceState = when {
                conn.hasActivePath() -> "ONLINE"
                conn.allPaths().isNotEmpty() -> "REACHABLE"
                else -> "OFFLINE"
            }

            // Delivery summary for this peer
            val peerMessages = uiMessages.filter { it.senderPeerId == peerIdVal || (it.isOutgoing && connectedPeerId?.value() == peerIdVal) }
            val deliveredCount = peerMessages.count { it.status == MessageDeliveryStatus.DELIVERED }
            val pendingCount = peerMessages.count { it.status == MessageDeliveryStatus.ACCEPTED || it.status == MessageDeliveryStatus.BUFFERED || it.status == MessageDeliveryStatus.DISPATCHED }
            val failedCount = peerMessages.count { it.status == MessageDeliveryStatus.FAILED }

            val isPeerSelected = connectedPeerId?.value() == peerIdVal

            peerContextsList.add(
                PeerContextState(
                    technicalPeerId = peerIdVal,
                    humanDisplayName = peerDisplayName,
                    presenceState = presenceState,
                    trustState = trustState.name,
                    isTrusted = isTrusted,
                    paths = peerPathsList,
                    deliverySummary = DeliverySummaryState(deliveredCount, pendingCount, failedCount),
                    isSelected = isPeerSelected
                )
            )
        }

        val snapshot = NetworkSnapshotState(
            peersCount = allConnectivities.size,
            activePathsCount = activePathsCount,
            trustedPeersCount = trustedPeersCount
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
                label = "YOU ($localDisplayName)"
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

        val activePeerContext = peerContextsList.firstOrNull { it.technicalPeerId == connectedPeerId?.value() }

        return DiagnosticState(
            nodeStatus = nodeStatus,
            snapshot = snapshot,
            peers = peersList,
            paths = pathsList,
            operations = operations,
            liveWireEvents = liveEvents,
            topology = topology,
            bufferState = bufferState,
            activePeerContext = activePeerContext,
            peerContexts = peerContextsList,
            conversationMessages = uiMessages
        )
    }

    /**
     * Formats technical peer ID into a human-readable display name.
     * Preserves the immutable technical ID underneath.
     */
    fun formatHumanDisplayName(peerIdStr: String): String {
        return when {
            peerIdStr.startsWith("android-") -> "Android (" + peerIdStr.removePrefix("android-").take(6) + ")"
            peerIdStr.startsWith("node-") -> "Node (" + peerIdStr.removePrefix("node-").take(6) + ")"
            peerIdStr.startsWith("remote-bt-") -> "Bluetooth Device (" + peerIdStr.removePrefix("remote-bt-").take(6) + ")"
            peerIdStr.length > 16 -> peerIdStr.take(12) + ".."
            else -> peerIdStr
        }
    }
}