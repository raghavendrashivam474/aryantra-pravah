package com.aryntra.pravah.android.state

import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState

/**
 * A.D3: Persistent Pravaah Cockpit State.
 * Integrates Peer Context, Human-first Messaging, Message Journey,
 * Delivery Outbox truth (B.R3), and Trust Lifecycle (SX.4).
 * Backward-compatible with A.D2 and A.D1 diagnostic consumers.
 */
data class DiagnosticState(
    val nodeStatus: NodeStatusState,
    val snapshot: NetworkSnapshotState,
    val peers: List<PeerItemState>,
    val paths: List<PathItemState>,
    val operations: OperationsState,
    val liveWireEvents: List<LiveWireEvent> = emptyList(),
    val topology: TopologyState = TopologyState(),
    val bufferState: TransitionBufferState = TransitionBufferState(),
    // A.D3 Additions
    val activePeerContext: PeerContextState? = null,
    val peerContexts: List<PeerContextState> = emptyList(),
    val conversationMessages: List<UiMessageItem> = emptyList()
)

data class NodeStatusState(
    val isRunning: Boolean,
    val localPeerId: String,
    val tcpPortStr: String,
    val discoveryActive: Boolean,
    val activeDiscoveryPort: Int,
    val connectedPeerId: String,
    val sessionState: String,
    // A.D3: Human displayName for local node
    val localDisplayName: String = localPeerId
)

data class NetworkSnapshotState(
    val peersCount: Int,
    val activePathsCount: Int,
    val trustedPeersCount: Int = 0
)

data class PeerItemState(
    val peerId: String,
    val sessionState: String,
    val resolvedRoute: String,
    // A.D3: Trust and human name attributes
    val displayName: String = peerId,
    val trustState: String = "UNKNOWN",
    val isTrusted: Boolean = false
)

data class PathItemState(
    val peerId: String,
    val transportType: String,
    val isActive: Boolean,
    val connectionId: String,
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

data class LiveWireEvent(
    val timestamp: String,
    val direction: String,
    val eventType: String = direction,
    val detail: String = ""
)

// ============================================================
// Topology Model (Preserved & Human-Enhanced for A.D3)
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
// B.R2/B.R3 Transition Buffer Observability
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

// ============================================================
// A.D3 NEW MODELS — Peer Context, Delivery & Message Journey
// ============================================================

/**
 * A.D3 Peer Context combining identity, presence, trust, paths, and delivery.
 * Section 8: Peer becomes the central context.
 */
data class PeerContextState(
    val technicalPeerId: String,
    val humanDisplayName: String,
    val presenceState: String, // ONLINE, REACHABLE, OFFLINE
    val trustState: String,    // UNKNOWN, AUTHENTICATING, AUTHENTICATED, TRUSTED, REJECTED
    val isTrusted: Boolean,
    val paths: List<PathItemState>,
    val deliverySummary: DeliverySummaryState = DeliverySummaryState(),
    val isSelected: Boolean = false
)

data class DeliverySummaryState(
    val deliveredCount: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0
)

/**
 * Delivery status backing the human checkmarks.
 * Backed by B.R3 Outbox / MessageState.
 */
enum class MessageDeliveryStatus {
    ACCEPTED,
    BUFFERED,
    PATH_SWITCHING,
    DISPATCHED,
    DELIVERED,
    FAILED
}

/**
 * Progressive disclosure Levels 1-4 for a message.
 */
data class MessageJourneyState(
    val messageId: String,
    val sequenceNumber: Long = 0,
    val destinationPeerId: String,
    val humanStatus: String,         // Level 1: "✓ Delivered", "◌ Sending..."
    val networkAwareStatus: String,  // Level 2: "✓ Delivered via Bluetooth"
    val technicalSummary: String,    // Level 3: "TCP dropped → BT selected; ACK received"
    val steps: List<MessageJourneyStep> = emptyList(),
    val forensicLog: List<String> = emptyList() // Level 4: exact timestamps and wire events
)

data class MessageJourneyStep(
    val title: String,        // e.g. "Accepted", "Buffered", "Dispatched", "Delivered"
    val description: String,  // e.g. "Message accepted locally into outbox"
    val timestamp: String,
    val isCompleted: Boolean,
    val isCurrent: Boolean = false
)

/**
 * Human-first messaging item shown on the chat surface.
 */
data class UiMessageItem(
    val messageId: String,
    val senderPeerId: String,
    val senderDisplayName: String,
    val content: String,
    val timestamp: String,
    val isOutgoing: Boolean,
    val status: MessageDeliveryStatus,
    val journey: MessageJourneyState
)