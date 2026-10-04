package com.aryntra.pravah.android.presentation

import com.aryntra.pravah.android.state.PathItemState

/**
 * A.D2: Renders active multi-path topologies and dispatch routes (§11).
 * Extended to display core state enums (ACTIVE/CANDIDATE/INACTIVE) 
 * and render selected route accents.
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
        return " └── [DISPATCH ROUTE]: $resolvedRoute\n\n"
    }
}
