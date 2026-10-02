package com.aryntra.pravah.peer.discovery;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathId;
import com.aryntra.pravah.peer.PeerId;

import java.util.Objects;

/**
 * Transport-agnostic representation of a discovered reachability candidate for a peer.
 *
 * <p>Carries the logical identity ({@link PeerId}), the transport mechanism (e.g., "tcp", "bluetooth"),
 * and the specific endpoint address without presuming an active connection exists.</p>
 *
 * S8.3 - Phase 8: Hybrid Discovery & Addressing
 */
public record DiscoveredAddressCandidate(
        PeerId peerId,
        String transportScheme,
        EndpointAddress endpointAddress
) {
    public DiscoveredAddressCandidate {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(transportScheme, "transportScheme must not be null");
        if (transportScheme.isBlank()) {
            throw new IllegalArgumentException("transportScheme must not be blank");
        }
        Objects.requireNonNull(endpointAddress, "endpointAddress must not be null");
    }

    /**
     * Factory for TCP IP-based reachability candidate.
     */
    public static DiscoveredAddressCandidate tcp(PeerId peerId, String host, int port) {
        return new DiscoveredAddressCandidate(peerId, "tcp", EndpointAddress.tcp(host, port));
    }

    /**
     * Factory for Bluetooth RFCOMM reachability candidate.
     */
    public static DiscoveredAddressCandidate bluetooth(PeerId peerId, String macAddress, int channel) {
        return new DiscoveredAddressCandidate(peerId, "bluetooth", EndpointAddress.of("bluetooth", macAddress, channel));
    }

    /**
     * Computes the canonical, deterministic PathId for this candidate.
     * Guaranteed to produce identical PathIds for identical peer + transport + endpoint tuples.
     */
    public PathId toPathId() {
        return PathId.of("path:" + peerId.value() + ":" + transportScheme.toLowerCase() + ":" + endpointAddress.host() + ":" + endpointAddress.port());
    }

    /**
     * Converts this discovery candidate into a ConnectivityPath in the CANDIDATE state.
     */
    public ConnectivityPath toCandidatePath() {
        return ConnectivityPath.candidate(toPathId(), peerId, transportScheme, endpointAddress);
    }
}