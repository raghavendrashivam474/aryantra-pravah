package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.peer.PeerId;

/**
 * Interface to receive asynchronous presence updates for known peers.
 *
 * S3.5 - Phase 3: Peer Presence
 */
@FunctionalInterface
public interface PeerPresenceListener {

    /**
     * Invoked when a peer transitions from one presence state to another.
     *
     * @param peerId logical identity of the peer
     * @param oldPresence snapshot of the previous presence (never null)
     * @param newPresence snapshot of the updated presence (never null)
     */
    void onPresenceChanged(PeerId peerId, PeerPresence oldPresence, PeerPresence newPresence);
}