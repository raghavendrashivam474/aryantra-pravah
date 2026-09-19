package com.aryntra.pravah.connectivity;

/**
 * Lifecycle state of a communication path.
 *
 * S6.1 - Phase 6: Connectivity Evolution
 */
public enum PathState {
    /** Known reachable endpoint, but no active transport connection is open. */
    CANDIDATE,

    /** Active transport connection is open and ready for data transfer. */
    ACTIVE,

    /** Path was previously usable but is currently disconnected or closed. */
    INACTIVE
}
