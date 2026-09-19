package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value representation of a communication path to a peer.
 *
 * <p>A ConnectivityPath captures:
 * <ul>
 *   <li><b>Identity:</b> WHICH peer this path reaches ({@link PeerId})</li>
 *   <li><b>Transport:</b> HOW the path operates (transport name/type)</li>
 *   <li><b>Address:</b> WHERE the peer is located ({@link EndpointAddress})</li>
 *   <li><b>State:</b> Current availability ({@link PathState})</li>
 *   <li><b>Connection:</b> Active transport connection ID, if established</li>
 * </ul>
 * </p>
 *
 * S6.1 - Phase 6: Connectivity Evolution
 */
public record ConnectivityPath(
        PathId pathId,
        PeerId peerId,
        String transportName,
        EndpointAddress endpointAddress,
        PathState state,
        String connectionId
) {

    public ConnectivityPath {
        Objects.requireNonNull(pathId, "pathId must not be null");
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(transportName, "transportName must not be null");
        if (transportName.isBlank()) {
            throw new IllegalArgumentException("transportName must not be blank");
        }
        Objects.requireNonNull(endpointAddress, "endpointAddress must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }

    /**
     * Factory for creating a candidate path from a discovered or configured endpoint.
     */
    public static ConnectivityPath candidate(PathId pathId, PeerId peerId, String transportName, EndpointAddress endpoint) {
        return new ConnectivityPath(pathId, peerId, transportName, endpoint, PathState.CANDIDATE, null);
    }

    /**
     * Factory for creating an active path with an established connection ID.
     */
    public static ConnectivityPath active(PathId pathId, PeerId peerId, String transportName, EndpointAddress endpoint, String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null for active path");
        if (connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank for active path");
        }
        return new ConnectivityPath(pathId, peerId, transportName, endpoint, PathState.ACTIVE, connectionId);
    }

    /**
     * Factory for creating an inactive/closed path.
     */
    public static ConnectivityPath inactive(PathId pathId, PeerId peerId, String transportName, EndpointAddress endpoint) {
        return new ConnectivityPath(pathId, peerId, transportName, endpoint, PathState.INACTIVE, null);
    }

    /**
     * Returns true if this path is currently active and usable for data transmission.
     */
    public boolean isActive() {
        return state == PathState.ACTIVE && connectionId != null && !connectionId.isBlank();
    }

    /**
     * Returns true if this path is a candidate for establishing a connection.
     */
    public boolean isCandidate() {
        return state == PathState.CANDIDATE;
    }

    /**
     * Returns an Optional containing the active connection ID if present.
     */
    public Optional<String> optionalConnectionId() {
        return Optional.ofNullable(connectionId);
    }

    /**
     * Returns a new ConnectivityPath transitioning this path to ACTIVE with the given connection ID.
     */
    public ConnectivityPath activate(String newConnectionId) {
        return ConnectivityPath.active(this.pathId, this.peerId, this.transportName, this.endpointAddress, newConnectionId);
    }

    /**
     * Returns a new ConnectivityPath transitioning this path to INACTIVE.
     */
    public ConnectivityPath deactivate() {
        return ConnectivityPath.inactive(this.pathId, this.peerId, this.transportName, this.endpointAddress);
    }
}
