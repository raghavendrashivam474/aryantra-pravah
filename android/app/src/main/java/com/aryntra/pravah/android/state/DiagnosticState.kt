package com.aryntra.pravah.android.state

import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState

/**
 * Clean, immutable UI state holding information parsed from Core.
 * Follows the target architecture in §7-§13.
 */
data class DiagnosticState(
    val nodeStatus: NodeStatusState,
    val snapshot: NetworkSnapshotState,
    val peers: List<PeerItemState>,
    val paths: List<PathItemState>,
    val operations: OperationsState
)

data class NodeStatusState(
    val isRunning: Boolean,
    val localPeerId: String,
    val tcpPortStr: String,
    val discoveryActive: Boolean,
    val activeDiscoveryPort: Int,
    val connectedPeerId: String,
    val sessionState: String
)

data class NetworkSnapshotState(
    val peersCount: Int,
    val activePathsCount: Int
)

data class PeerItemState(
    val peerId: String,
    val sessionState: String,
    val resolvedRoute: String
)

data class PathItemState(
    val peerId: String,
    val transportType: String, // "TCP" or "BLUETOOTH"
    val isActive: Boolean,
    val connectionId: String
)

data class OperationsState(
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val discoveryText: String,
    val discoveryEnabled: Boolean,
    val connectTcpEnabled: Boolean,
    val connectBtEnabled: Boolean,
    val simulateDropEnabled: Boolean,
    val sendEnabled: Boolean
)

data class LiveWireEvent(
    val timestamp: String,
    val direction: String, // "TX", "RX", "SYSTEM", "ERROR"
    val transport: String, // "TCP", "BT", "UDP", "CORE"
    val message: String
)
