package com.aryntra.pravah.security.trust;

import com.aryntra.pravah.peer.PeerId;

/**
 * Observer interface for changes in a peer's trust lifecycle state.
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
@FunctionalInterface
public interface TrustStateListener {

    /**
     * Invoked when a peer's trust state changes.
     *
     * @param peerId   the peer whose state changed
     * @param oldState the previous trust state
     * @param newState the updated trust state
     */
    void onTrustStateChanged(PeerId peerId, TrustState oldState, TrustState newState);
}
