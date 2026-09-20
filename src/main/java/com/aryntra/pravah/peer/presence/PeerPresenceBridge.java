package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathId;
import com.aryntra.pravah.connectivity.PeerConnectivity;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRecord;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.discovery.DiscoveredPeer;
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Orchestrating coordinator linking LAN Discovery, Peer Registry, Presence Manager,
 * and (as of S6.4) the Connectivity Registry.
 *
 * <p>S6.4 Integration: When a peer is discovered, a CANDIDATE ConnectivityPath is
 * registered. When a connection is established, the candidate is promoted to ACTIVE.
 * When a connection closes, active paths are marked INACTIVE.</p>
 *
 * <p>Backward compatible: the original two-argument constructor preserves all
 * Phase 3–5 behavior without connectivity tracking.</p>
 *
 * S3.4 - S3.5 - Phase 3 Integration Bridge
 * S6.4 - Phase 6: Connectivity Integration
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
     *
     * @param registry             the peer registry
     * @param presenceManager      the presence manager
     * @param connectivityRegistry the connectivity registry (may be null to disable)
     */
    public PeerPresenceBridge(PeerRegistry registry,
                              PeerPresenceManager presenceManager,
                              PeerConnectivityRegistry connectivityRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.presenceManager = Objects.requireNonNull(presenceManager, "presenceManager must not be null");
        this.connectivityRegistry = connectivityRegistry; // intentionally nullable
    }

    /**
     * Handle automated LAN discoveries.
     *
     * <p>S6.4: Additionally registers a CANDIDATE ConnectivityPath for the
     * discovered endpoint. Duplicate discoveries produce the same deterministic
     * PathId and are therefore idempotent.</p>
     */
    @Override
    public void onPeerDiscovered(DiscoveredPeer peer) {
        Objects.requireNonNull(peer, "peer must not be null");

        // 1. Update/Report to Presence Manager (existing)
        presenceManager.reportDiscovered(peer.peerId(), peer.hostAddress(), peer.port());

        // 2. Register in PeerRegistry as known (but disconnected) if not already present (existing)
        if (!registry.contains(peer.peerId())) {
            logger.info("Registering newly discovered LAN peer: " + peer.peerId().value());
            registry.register(PeerRecord.disconnected(peer.peerId()));
        }

        // 3. S6.4: Register a CANDIDATE connectivity path
        if (connectivityRegistry != null) {
            registerCandidatePath(peer);
        }
    }

    /**
     * Explicitly coordinate a successful connection session.
     *
     * <p>S6.4: Promotes the first CANDIDATE path to ACTIVE with the given
     * connectionId. If no candidate exists, creates a new ACTIVE path.</p>
     */
    public void handlePeerConnected(PeerId peerId, String connectionId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");

        // 1. Update Registry with the active connection identifier (existing)
        registry.register(peerId, connectionId);

        // 2. Update Presence to CONNECTED (existing)
        presenceManager.reportConnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked CONNECTED.");

        // 3. S6.4: Activate a connectivity path
        if (connectivityRegistry != null) {
            activatePath(peerId, connectionId);
        }
    }

    /**
     * Explicitly coordinate a connection tear-down or drop event.
     *
     * <p>S6.4: Deactivates all ACTIVE paths for this peer. The peer identity
     * and candidate paths are preserved.</p>
     */
    public void handlePeerDisconnected(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");

        // 1. Downgrade/Update Registry to disconnected state (existing)
        registry.register(PeerRecord.disconnected(peerId));

        // 2. Update Presence (existing)
        presenceManager.reportDisconnected(peerId);
        logger.fine("Presence Bridge: Peer " + peerId.value() + " marked disconnected.");

        // 3. S6.4: Deactivate all active connectivity paths
        if (connectivityRegistry != null) {
            deactivatePaths(peerId);
        }
    }

    // ── S6.4 Private Helpers ──────────────────────────────────────────

    /**
     * Registers a deterministic CANDIDATE path from a discovered peer.
     * The PathId is derived from peerId + transport + host + port, making
     * repeated discoveries of the same endpoint idempotent.
     */
    private void registerCandidatePath(DiscoveredPeer peer) {
        String deterministicId = "path:" + peer.peerId().value()
                + ":tcp:" + peer.hostAddress() + ":" + peer.port();
        PathId pathId = PathId.of(deterministicId);
        EndpointAddress endpoint = EndpointAddress.tcp(peer.hostAddress(), peer.port());
        ConnectivityPath candidate = ConnectivityPath.candidate(pathId, peer.peerId(), "tcp", endpoint);
        connectivityRegistry.registerPath(peer.peerId(), candidate);
        logger.fine("Connectivity: Registered CANDIDATE path " + pathId + " for peer " + peer.peerId().value());
    }

    /**
     * Promotes the first CANDIDATE path to ACTIVE, or creates a new ACTIVE path
     * if no candidate exists.
     */
    private void activatePath(PeerId peerId, String connectionId) {
        PeerConnectivity connectivity = connectivityRegistry.getOrCreate(peerId);
        List<ConnectivityPath> candidates = connectivity.candidatePaths();

        if (!candidates.isEmpty()) {
            ConnectivityPath candidate = candidates.get(0);
            connectivity.addPath(candidate.activate(connectionId));
            logger.fine("Connectivity: Activated path " + candidate.pathId()
                    + " with connection " + connectionId);
        } else {
            // No prior candidate — connection was established without discovery.
            // Create a synthetic active path so connectivity state is consistent.
            PathId pathId = PathId.of("path:" + peerId.value() + ":tcp:conn:" + connectionId);
            EndpointAddress endpoint = EndpointAddress.tcp("unknown", 0);
            ConnectivityPath active = ConnectivityPath.active(pathId, peerId, "tcp", endpoint, connectionId);
            connectivity.addPath(active);
            logger.fine("Connectivity: Created ACTIVE path " + pathId
                    + " for connection " + connectionId);
        }
    }

    /**
     * Deactivates all ACTIVE paths for a peer. Candidate and already-inactive
     * paths are preserved.
     */
    private void deactivatePaths(PeerId peerId) {
        connectivityRegistry.lookup(peerId).ifPresent(connectivity -> {
            for (ConnectivityPath path : connectivity.activePaths()) {
                connectivity.addPath(path.deactivate());
                logger.fine("Connectivity: Deactivated path " + path.pathId());
            }
        });
    }
}