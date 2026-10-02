package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathId;
import com.aryntra.pravah.connectivity.PeerConnectivity;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRecord;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.discovery.DiscoveredAddressCandidate;
import com.aryntra.pravah.peer.discovery.DiscoveredPeer;
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Orchestrating coordinator linking LAN & Bluetooth Discovery, Peer Registry, Presence Manager,
 * and Connectivity Registry.
 *
 * <p>S6.4 & S8.3 Integration: When a reachability candidate is discovered via any transport,
 * an idempotent CANDIDATE ConnectivityPath is registered. Multiple distinct transport candidates
 * for the same PeerId will live side-by-side in the PeerConnectivity record.</p>
 *
 * S3.4 - S3.5 - Phase 3 Integration Bridge
 * S6.4 - Phase 6: Connectivity Integration
 * S8.3 - Phase 8: Hybrid Discovery & Addressing
 */
public final class PeerPresenceBridge implements PeerDiscoveryListener {
    private static final Logger logger = Logger.getLogger(PeerPresenceBridge.class.getName());

    private final PeerRegistry registry;
    private final PeerPresenceManager presenceManager;
    private final PeerConnectivityRegistry connectivityRegistry; // nullable for backward compat

    /**
     * Original Phase 3 constructor — no connectivity tracking.
     */
    public PeerPresenceBridge(PeerRegistry registry, PeerPresenceManager presenceManager) {
        this(registry, presenceManager, null);
    }

    /**
     * S6.4 constructor — enables connectivity path lifecycle tracking.
     */
    public PeerPresenceBridge(PeerRegistry registry,
                              PeerPresenceManager presenceManager,
                              PeerConnectivityRegistry connectivityRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.presenceManager = Objects.requireNonNull(presenceManager, "presenceManager must not be null");
        this.connectivityRegistry = connectivityRegistry; // intentionally nullable
    }

    /**
     * Handle automated LAN discoveries (TCP IP endpoint).
     */
    @Override
    public void onPeerDiscovered(DiscoveredPeer peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        DiscoveredAddressCandidate candidate = DiscoveredAddressCandidate.tcp(
                peer.peerId(), peer.hostAddress(), peer.port()
        );
        onCandidateDiscovered(candidate);
    }

    /**
     * S8.3: Universal reachability candidate handler across heterogeneous transports.
     * Idempotently creates a CANDIDATE path and registers peer presence without marking active.
     */
    public void onCandidateDiscovered(DiscoveredAddressCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        PeerId peerId = candidate.peerId();

        // 1. Update/Report to Presence Manager
        presenceManager.reportDiscovered(peerId, candidate.endpointAddress().host(), candidate.endpointAddress().port());

        // 2. Register in PeerRegistry as known (disconnected) if not already present
        if (!registry.contains(peerId)) {
            logger.info("Registering newly discovered peer: " + peerId.value());
            registry.register(PeerRecord.disconnected(peerId));
        }

        // 3. Register deterministic CANDIDATE connectivity path
        if (connectivityRegistry != null) {
            ConnectivityPath path = candidate.toCandidatePath();
            connectivityRegistry.registerPath(peerId, path);
            logger.fine("Connectivity: Registered CANDIDATE path " + path.pathId() + " for peer " + peerId.value());
        }
    }

    /**
     * Explicitly coordinate a successful connection session.
     * Promotes matching candidate path to ACTIVE, or creates an active path.
     */
    public void handlePeerConnected(PeerId peerId, String connectionId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");

        // 1. Update Registry with the active connection identifier
        registry.register(peerId, connectionId);

        // 2. Update Presence to CONNECTED
        presenceManager.reportConnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked CONNECTED.");

        // 3. Activate connectivity path
        if (connectivityRegistry != null) {
            activatePath(peerId, connectionId);
        }
    }

    /**
     * Explicitly coordinate a connection tear-down or drop event for all paths.
     */
    public void handlePeerDisconnected(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");

        // 1. Downgrade/Update Registry to disconnected state
        registry.register(PeerRecord.disconnected(peerId));

        // 2. Update Presence
        presenceManager.reportDisconnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked disconnected.");

        // 3. Deactivate all active connectivity paths
        if (connectivityRegistry != null) {
            deactivatePaths(peerId);
        }
    }

    /**
     * S8.3: Deactivate a specific path by connection ID without dropping other paths.
     */
    public void handleConnectionClosed(PeerId peerId, String connectionId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        if (connectivityRegistry != null) {
            connectivityRegistry.lookup(peerId).ifPresent(connectivity -> {
                for (ConnectivityPath path : connectivity.activePaths()) {
                    if (Objects.equals(path.connectionId(), connectionId)) {
                        connectivity.addPath(path.deactivate());
                        logger.fine("Connectivity: Deactivated specific path " + path.pathId());
                    }
                }
                // If no active paths remain, mark peer disconnected in registry & presence
                if (!connectivity.hasActivePath()) {
                    registry.register(PeerRecord.disconnected(peerId));
                    presenceManager.reportDisconnected(peerId);
                }
            });
        } else {
            handlePeerDisconnected(peerId);
        }
    }

    private void activatePath(PeerId peerId, String connectionId) {
        PeerConnectivity connectivity = connectivityRegistry.getOrCreate(peerId);
        List<ConnectivityPath> candidates = connectivity.candidatePaths();
        
        // Find matching candidate by connection scheme if possible
        String targetScheme = connectionId.startsWith("bt:") ? "bluetooth" : "tcp";
        ConnectivityPath targetCandidate = candidates.stream()
                .filter(c -> c.transportName().equalsIgnoreCase(targetScheme))
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));

        if (targetCandidate != null) {
            connectivity.addPath(targetCandidate.activate(connectionId));
            logger.fine("Connectivity: Activated path " + targetCandidate.pathId()
                    + " with connection " + connectionId);
        } else {
            // Create synthetic active path
            PathId pathId = PathId.of("path:" + peerId.value() + ":" + targetScheme + ":conn:" + connectionId);
            EndpointAddress endpoint = targetScheme.equals("bluetooth")
                    ? EndpointAddress.of("bluetooth", connectionId.substring(3), 1)
                    : EndpointAddress.tcp("unknown", 0);
            ConnectivityPath active = ConnectivityPath.active(pathId, peerId, targetScheme, endpoint, connectionId);
            connectivity.addPath(active);
            logger.fine("Connectivity: Created ACTIVE path " + pathId + " for connection " + connectionId);
        }
    }

    private void deactivatePaths(PeerId peerId) {
        connectivityRegistry.lookup(peerId).ifPresent(connectivity -> {
            for (ConnectivityPath path : connectivity.activePaths()) {
                connectivity.addPath(path.deactivate());
                logger.fine("Connectivity: Deactivated path " + path.pathId());
            }
        });
    }
}