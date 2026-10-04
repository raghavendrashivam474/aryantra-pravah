package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;

/**
 * Callback interface for observing path state transitions in the connectivity layer.
 *
 * <p>Introduced in B.R2 to enable transition-window buffering: PeerRouter subscribes
 * to path activations so it can flush its in-flight buffer when a CANDIDATE path
 * becomes ACTIVE.</p>
 *
 * <p>Implementations must be thread-safe. Callbacks may fire from any thread
 * that mutates path state (typically the transport I/O thread).</p>
 *
 * B.R2 — Bounded In-Flight Buffering & Transition Reliability
 */
@FunctionalInterface
public interface PathStateListener {

    /**
     * Called when a path transitions to a new state.
     *
     * @param peerId        the peer whose path changed
     * @param path          the path in its NEW state (after transition)
     * @param previousState the state BEFORE the transition
     */
    void onPathStateChanged(PeerId peerId, ConnectivityPath path, PathState previousState);
}