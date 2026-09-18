package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRecord;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.discovery.DiscoveredPeer;
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Orchestrating coordinator linking LAN Discovery, Peer Registry, and Presence Manager
 * without modifying existing transport or protocol contracts.
 *
 * S3.4 - S3.5 - Phase 3 Integration Bridge
 */
public final class PeerPresenceBridge implements PeerDiscoveryListener {

    private static final Logger logger = Logger.getLogger(PeerPresenceBridge.class.getName());

    private final PeerRegistry registry;
    private final PeerPresenceManager presenceManager;

    public PeerPresenceBridge(PeerRegistry registry, PeerPresenceManager presenceManager) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.presenceManager = Objects.requireNonNull(presenceManager, "presenceManager must not be null");
    }

    /**
     * Handle automated LAN discoveries.
     */
    @Override
    public void onPeerDiscovered(DiscoveredPeer peer) {
        Objects.requireNonNull(peer, "peer must not be null");

        // 1. Update/Report to Presence Manager
        presenceManager.reportDiscovered(peer.peerId(), peer.hostAddress(), peer.port());

        // 2. Register in PeerRegistry as known (but disconnected) if not already present
        if (!registry.contains(peer.peerId())) {
            logger.info("Registering newly discovered LAN peer: " + peer.peerId().value());
            registry.register(PeerRecord.disconnected(peer.peerId()));
        }
    }

    /**
     * Explicitly coordinate a successful connection session.
     */
    public void handlePeerConnected(PeerId peerId, String connectionId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");

        // 1. Update Registry with the active connection identifier
        registry.register(peerId, connectionId);

        // 2. Update Presence to CONNECTED
        presenceManager.reportConnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked CONNECTED.");
    }

    /**
     * Explicitly coordinate a connection tear-down or drop event.
     */
    public void handlePeerDisconnected(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");

        // 1. Downgrade/Update Registry to disconnected state (null connection ID)
        registry.register(PeerRecord.disconnected(peerId));

        // 2. Update Presence (transitions to AVAILABLE or UNAVAILABLE based on TTL)
        presenceManager.reportDisconnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked disconnected.");
    }
}