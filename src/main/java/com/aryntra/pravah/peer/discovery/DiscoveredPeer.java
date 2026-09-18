package com.aryntra.pravah.peer.discovery;

import com.aryntra.pravah.peer.PeerId;

import java.util.Objects;

/**
 * Immutable value object representing a peer discovered on the local network.
 *
 * <p>Separates identity ({@link PeerId} = WHO) from reachable network
 * endpoint ({@code hostAddress} + {@code port} = WHERE).</p>
 *
 * S3.4 - Phase 3: LAN Peer Discovery
 */
public record DiscoveredPeer(
        PeerId peerId,
        String hostAddress,
        int port
) {

    public DiscoveredPeer {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(hostAddress, "hostAddress must not be null");
        if (hostAddress.isBlank()) {
            throw new IllegalArgumentException("hostAddress must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 1-65535, got: " + port);
        }
    }

    /**
     * Convenience factory using string peer ID and endpoint coordinates.
     */
    public static DiscoveredPeer of(String peerIdValue, String hostAddress, int port) {
        return new DiscoveredPeer(PeerId.of(peerIdValue), hostAddress, port);
    }
}