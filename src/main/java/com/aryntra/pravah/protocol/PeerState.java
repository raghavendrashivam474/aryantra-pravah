package com.aryntra.pravah.protocol;

/**
 * Represents the logical lifecycle state of a peer in the Pravah protocol session.
 * This is strictly a protocol-level state and is independent of physical transport connectivity.
 */
public enum PeerState {
    /**
     * The peer has explicitly announced presence via a valid JOIN message.
     */
    JOINED,

    /**
     * The peer has explicitly announced departure via a valid LEAVE message.
     */
    LEFT
}