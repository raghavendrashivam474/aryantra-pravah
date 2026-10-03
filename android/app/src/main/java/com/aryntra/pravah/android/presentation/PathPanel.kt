package com.aryntra.pravah.android.presentation

import com.aryntra.pravah.android.state.PathItemState

/**
 * Renders active multi-path topologies and dynamic dispatch routes (§11).
 */
class PathPanel {
    fun formatPath(state: PathItemState): String {
        val symbol = if (state.isActive) "● ACTIVE" else "○ INACTIVE"
        return " ├── [${state.transportType}] $symbol (${state.connectionId})\n"
    }

    fun formatDispatchRoute(resolvedRoute: String): String {
        return " └── [DISPATCH ROUTE]: $resolvedRoute\n\n"
    }
}
