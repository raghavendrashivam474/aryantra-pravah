package com.aryntra.pravah.android.presentation

import com.aryntra.pravah.android.state.PeerItemState

/**
 * Renders active peer registration parameters (§10).
 */
class PeerPanel {
    fun formatPeerHeader(state: PeerItemState): String {
        return "Peer: ${state.peerId} [${state.sessionState}]\n"
    }
}
