package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.peer.PeerId;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only snapshot of a peer's presence state and metadata coordinates.
 *
 * S3.5 - Phase 3: Peer Presence
 */
public record PeerPresence(
        PeerId peerId,
        PeerPresenceState state,
        String hostAddress,
        int port,
        Instant lastSeen
) {

    public PeerPresence {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(lastSeen, "lastSeen must not be null");
    }

    /**
     * Creates an initial default presence entry for a peer.
     */
    public static PeerPresence unknown(PeerId peerId) {
        return new PeerPresence(peerId, PeerPresenceState.UNKNOWN, null, 0, Instant.EPOCH);
    }
}