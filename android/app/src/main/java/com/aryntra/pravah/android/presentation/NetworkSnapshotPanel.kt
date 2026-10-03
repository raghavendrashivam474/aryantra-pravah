package com.aryntra.pravah.android.presentation

import com.aryntra.pravah.android.state.NetworkSnapshotState

/**
 * Lightweight snapshot component (§9) mapping current telemetry numbers.
 * Acts as an architectural anchor for future A.D2 metrics.
 */
class NetworkSnapshotPanel {
    fun formatTelemetry(state: NetworkSnapshotState): String {
        val peersFormatted = String.format("%02d", state.peersCount)
        val pathsFormatted = String.format("%02d", state.activePathsCount)
        return "PEERS: $peersFormatted | ACTIVE PATHS: $pathsFormatted"
    }
}
