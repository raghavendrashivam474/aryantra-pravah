package com.aryntra.pravah.peer;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record capturing the current communication metadata of a known logical peer.
 *
 * <p>A PeerRecord associates a logical {@link PeerId} with an active transport connection identifier
 * (e.g. TCP connection ID), if one is currently established. Transport details remain decoupled.</p>
 *
 * S3.2 - Phase 3: Peer Registry
 */
public record PeerRecord(
        PeerId peerId,
        String connectionId
) {

    public PeerRecord {
        Objects.requireNonNull(peerId, "peerId must not be null");
    }

    /**
     * Creates a PeerRecord for a known peer that currently has an active transport connection.
     */
    public static PeerRecord connected(PeerId peerId, String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null for connected peer");
        return new PeerRecord(peerId, connectionId);
    }

    /**
     * Creates a PeerRecord for a peer that is known but currently disconnected.
     */
    public static PeerRecord disconnected(PeerId peerId) {
        return new PeerRecord(peerId, null);
    }

    /**
     * Returns true if this peer currently has an active transport connection reference.
     */
    public boolean isConnected() {
        return connectionId != null && !connectionId.isBlank();
    }

    /**
     * Returns an Optional containing the connection ID if connected.
     */
    public Optional<String> optionalConnectionId() {
        return Optional.ofNullable(connectionId);
    }

    /**
     * Returns a new PeerRecord with the specified connection ID.
     */
    public PeerRecord withConnectionId(String newConnectionId) {
        return new PeerRecord(this.peerId, newConnectionId);
    }
}