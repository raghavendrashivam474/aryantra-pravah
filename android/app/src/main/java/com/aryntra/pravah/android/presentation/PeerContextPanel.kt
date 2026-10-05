package com.aryntra.pravah.android.presentation

import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.aryntra.pravah.android.state.PeerContextState

/**
 * A.D3: Peer Context Presentation Panel.
 * Section 8: Peer becomes the central UI context.
 * Combines Human Identity, Technical ID, Presence, Trust, Paths, and Delivery.
 */
class PeerContextPanel(
    private val tvPeerContext: TextView
) {
    fun render(peerContext: PeerContextState?) {
        if (peerContext == null) {
            tvPeerContext.text = "No peer selected. Discover or connect to a node to begin."
            tvPeerContext.setTextColor(Color.parseColor("#8892b0"))
            return
        }

        val sb = StringBuilder()
        
        // 1. Identity & Presence
        val presenceColor = when (peerContext.presenceState) {
            "ONLINE" -> "● Online"
            "REACHABLE" -> "◐ Reachable"
            else -> "○ Offline"
        }
        sb.append("${peerContext.humanDisplayName}  [$presenceColor]\n")
        sb.append("ID: ${peerContext.technicalPeerId}\n\n")

        // 2. Trust State (SX.4)
        val trustDisplay = when (peerContext.trustState) {
            "TRUSTED" -> "✓ TRUSTED (Cryptographically Verified)"
            "AUTHENTICATING" -> "◌ AUTHENTICATING..."
            "REJECTED" -> "✗ REJECTED / UNTRUSTED"
            else -> "? UNKNOWN (Unverified)"
        }
        sb.append("TRUST\n$trustDisplay\n\n")

        // 3. Physical Paths
        sb.append("PATHS\n")
        if (peerContext.paths.isEmpty()) {
            sb.append("  (No physical transport paths)\n")
        } else {
            for (path in peerContext.paths) {
                val stateSymbol = if (path.isActive) "●" else "○"
                val selectedTag = if (path.isSelected) " [SELECTED ROUTE]" else ""
                sb.append("  ${path.transportType.padEnd(10)} $stateSymbol ${path.pathState}$selectedTag\n")
            }
        }
        sb.append("\n")

        // 4. Delivery Summary (B.R3)
        sb.append("DELIVERY SUMMARY\n")
        val ds = peerContext.deliverySummary
        sb.append("  Delivered: ${ds.deliveredCount} | Pending: ${ds.pendingCount} | Failed: ${ds.failedCount}")

        tvPeerContext.text = sb.toString()
        tvPeerContext.setTextColor(Color.parseColor("#e0e0e0"))
    }
}