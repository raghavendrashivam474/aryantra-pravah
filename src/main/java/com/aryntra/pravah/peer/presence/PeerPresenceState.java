package com.aryntra.pravah.peer.presence;

/**
 * Enumeration of possible presence states for a peer.
 *
 * S3.5 - Phase 3: Peer Presence
 */
public enum PeerPresenceState {
    /** The system has no current record or observation of this peer. */
    UNKNOWN,

    /** Peer announced on LAN (known coordinates) but no active TCP session. */
    AVAILABLE,

    /** Active transport connection is established. */
    CONNECTED,

    /** Peer was previously known but went silent or connection dropped. */
    UNAVAILABLE
}