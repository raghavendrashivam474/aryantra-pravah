package com.aryntra.pravah.peer.discovery;

/**
 * Listener interface for LAN peer discovery notifications.
 *
 * S3.4 - Phase 3: LAN Peer Discovery
 */
@FunctionalInterface
public interface PeerDiscoveryListener {

    /**
     * Invoked when a valid Pravah peer is discovered on the local network.
     *
     * @param peer the discovered peer details (never null)
     */
    void onPeerDiscovered(DiscoveredPeer peer);
}