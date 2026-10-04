package com.aryntra.pravah.android.presentation

import com.aryntra.pravah.android.state.NetworkSnapshotState
import com.aryntra.pravah.android.state.TopologyState

/**
 * A.D2: Evolved network snapshot panel.
 * Represents real multi-path relationships and builds ASCII topology maps.
 * Never fabricates telemetry (§3).
 */
class NetworkSnapshotPanel {
    fun formatTelemetry(state: NetworkSnapshotState): String {
        val peersFormatted = String.format("%02d", state.peersCount)
        val pathsFormatted = String.format("%02d", state.activePathsCount)
        return "PEERS: $peersFormatted | ACTIVE PATHS: $pathsFormatted"
    }

    fun renderAsciiTopology(topology: TopologyState): String {
        if (topology.peers.isEmpty()) {
            return "     LOCAL NODE (${topology.localNode.label})\n" +
                   "     └─ [No connected peers discovered]\n"
        }

        val sb = StringBuilder()
        sb.append("     ┌──────────────────────────────────────┐\n")
        sb.append("     │        LIVE NETWORK TOPOLOGY         │\n")
        sb.append("     └──────────────────────────────────────┘\n\n")

        // Render each peer relative to the local node
        for (peer in topology.peers) {
            sb.append("             ┌───────────┐\n")
            sb.append("             │  PEER: ${peer.label.padEnd(8).take(8)} │\n")
            sb.append("             └─────┬─────┘\n")
            
            // Collect edges belonging to this peer
            val peerEdges = topology.edges.filter { it.toNodeId == peer.id }
            if (peerEdges.isEmpty()) {
                sb.append("                   │ [NO PATHS]\n")
            } else {
                for (edge in peerEdges) {
                    val statusText = edge.pathState
                    val selectionIndicator = if (edge.isSelected) " SELECTED" else ""
                    sb.append(String.format("                   ├─ %-9s: %-9s%s\n", edge.transportType, statusText, selectionIndicator))
                }
            }
            sb.append("                   │\n")
        }
        sb.append("                 LOCAL: ${topology.localNode.label}\n")
        return sb.toString()
    }
}
