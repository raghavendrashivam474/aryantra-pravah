package com.aryntra.pravah.android.presentation

import com.aryntra.pravah.android.state.PathItemState

/**
 * A.D2.1: Renders path connections and dispatch routes.
 * Issue E: Clearly distinguishes when a dispatch route is active vs NONE.
 */
class PathPanel {
    fun formatPath(state: PathItemState): String {
        val symbol = when (state.pathState) {
            "ACTIVE" -> "● ACTIVE"
            "CANDIDATE" -> "◐ CANDIDATE"
            else -> "○ INACTIVE"
        }
        val selectionAccent = if (state.isSelected) " [SELECTED ROUTE]" else ""
        return " ├── [${state.transportType}] $symbol (${state.connectionId})$selectionAccent\n"
    }

    fun formatDispatchRoute(resolvedRoute: String): String {
        val display = if (resolvedRoute.isBlank() || resolvedRoute == "NONE") "NONE (No active path)" else resolvedRoute
        return " └── [DISPATCH ROUTE]: $display\n\n"
    }
}
