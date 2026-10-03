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
 */
public final class PeerPresenceBridge implements PeerDiscoveryListener {
    private static final Logger logger = Logger.getLogger(PeerPresenceBridge.class.getName());

    private final PeerRegistry registry;
    private final PeerPresenceManager presenceManager;
    private final PeerConnectivityRegistry connectivityRegistry; // nullable for backward compat

    public PeerPresenceBridge(PeerRegistry registry, PeerPresenceManager presenceManager) {
        this(registry, presenceManager, null);
    }

    public PeerPresenceBridge(PeerRegistry registry,
                              PeerPresenceManager presenceManager,
                              PeerConnectivityRegistry connectivityRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.presenceManager = Objects.requireNonNull(presenceManager, "presenceManager must not be null");
        this.connectivityRegistry = connectivityRegistry;
    }

    @Override
    public void onPeerDiscovered(DiscoveredPeer peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        DiscoveredAddressCandidate candidate = DiscoveredAddressCandidate.tcp(
                peer.peerId(), peer.hostAddress(), peer.port()
        );
        onCandidateDiscovered(candidate);
    }

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

    public void handlePeerDisconnected(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");

        registry.register(PeerRecord.disconnected(peerId));
        presenceManager.reportDisconnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked disconnected.");

        if (connectivityRegistry != null) {
            deactivatePaths(peerId);
        }
    }

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
        String targetScheme = connectionId.startsWith("bt:") ? "bluetooth" : "tcp";

        // Check for existing candidate or inactive path matching this scheme or connection
        ConnectivityPath targetPath = connectivity.allPaths().stream()
                .filter(p -> !p.isActive() && (p.transportName().equalsIgnoreCase(targetScheme)
                        || (p.connectionId() != null && p.connectionId().equals(connectionId))))
                .findFirst()
                .orElse(null);

        if (targetPath != null) {
            connectivity.addPath(targetPath.activate(connectionId));
            logger.fine("Connectivity: Activated path " + targetPath.pathId() + " with connection " + connectionId);
        } else {
            // Check if already active with same connectionId
            boolean alreadyActive = connectivity.activePaths().stream()
                    .anyMatch(p -> Objects.equals(p.connectionId(), connectionId));
            if (!alreadyActive) {
                PathId pathId = PathId.of("path:" + peerId.value() + ":" + targetScheme + ":conn:" + connectionId);
                EndpointAddress endpoint = targetScheme.equals("bluetooth")
                        ? EndpointAddress.of("bluetooth", connectionId.startsWith("bt:") ? connectionId.substring(3) : connectionId, 1)
                        : EndpointAddress.tcp("unknown", 0);
                ConnectivityPath active = ConnectivityPath.active(pathId, peerId, targetScheme, endpoint, connectionId);
                connectivity.addPath(active);
                logger.fine("Connectivity: Created ACTIVE path " + pathId + " for connection " + connectionId);
            }
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