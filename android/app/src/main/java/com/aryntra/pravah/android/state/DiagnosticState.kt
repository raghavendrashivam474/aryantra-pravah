package com.aryntra.pravah.android.state

import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState

/**
 * A.D2: Root diagnostic state.
 * Extended from A.D1 with liveWireEvents, topology, and bufferState.
 * All new fields have defaults to preserve A.D1 backward compatibility.
 */
data class DiagnosticState(
    val nodeStatus: NodeStatusState,
    val snapshot: NetworkSnapshotState,
    val peers: List<PeerItemState>,
    val paths: List<PathItemState>,
    val operations: OperationsState,
    // A.D2 additions
    val liveWireEvents: List<LiveWireEvent> = emptyList(),
    val topology: TopologyState = TopologyState(),
    val bufferState: TransitionBufferState = TransitionBufferState()
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

/**
 * A.D2: Extended with pathState (ACTIVE/CANDIDATE/INACTIVE from Core enum)
 * and isSelected (dispatch route distinction per Section 10).
 * isActive preserved for A.D1 backward compat.
 */
data class PathItemState(
    val peerId: String,
    val transportType: String,
    val isActive: Boolean,
    val connectionId: String,
    // A.D2 additions
    val pathState: String = if (isActive) "ACTIVE" else "INACTIVE",
    val isSelected: Boolean = false
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

/**
 * A.D2: Extended with eventType and detail for richer LiveWire display.
 * direction preserved for A.D1 backward compat.
 */
data class LiveWireEvent(
    val timestamp: String,
    val direction: String,
    // A.D2 additions
    val eventType: String = direction,
    val detail: String = ""
)

// ============================================================
// A.D2 NEW MODELS — Topology (Section 8-9)
// ============================================================

data class TopologyState(
    val localNode: TopologyNode = TopologyNode("LOCAL", "NODE"),
    val peers: List<TopologyNode> = emptyList(),
    val edges: List<TopologyEdge> = emptyList()
)

data class TopologyNode(
    val id: String,
    val label: String
)

data class TopologyEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val transportType: String,
    val pathState: String,
    val isSelected: Boolean = false
)

// ============================================================
// A.D2 NEW MODEL — B.R2 Observability (Section 18)
// ============================================================

data class TransitionBufferState(
    val currentSize: Int = 0,
    val currentBytes: Long = 0,
    val totalBuffered: Long = 0,
    val totalFlushed: Long = 0,
    val totalExpired: Long = 0,
    val totalEvicted: Long = 0,
    val totalRejected: Long = 0
)
