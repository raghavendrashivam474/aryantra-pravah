package com.aryntra.pravah.android.presentation

import android.widget.TextView
import com.aryntra.pravah.android.state.NodeStatusState

/**
 * Technical status header component (§8).
 * Renders local identity, TCP ports, UDP discovery and active session state.
 */
class NodeStatusPanel(private val tvStatus: TextView) {

    fun render(state: NodeStatusState) {
        val runState = if (state.isRunning) "RUNNING" else "STOPPED"
        val discState = if (state.discoveryActive) "ACTIVE" else "OFF"
        
        tvStatus.text = "Status: $runState | TCP Port: ${state.tcpPortStr}\n" +
                "PeerId: ${state.localPeerId}\n" +
                "Discovery: $discState | Remote: ${state.connectedPeerId} [${state.sessionState}]"
    }
}
