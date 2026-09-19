package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that maps logical {@link PeerId}s to their multi-path
 * {@link PeerConnectivity} records.
 *
 * <p>Allows a single PeerId to have multiple active, candidate, or inactive paths
 * concurrently, satisfying S6.2 requirements.</p>
 *
 * S6.2 - Phase 6: Multi-Path Peer Representation
 */
public class PeerConnectivityRegistry {

    private final ConcurrentHashMap<PeerId, PeerConnectivity> peerMap = new ConcurrentHashMap<>();

    /**
     * Retrieves or creates the PeerConnectivity record for a peer.
     *
     * @param peerId the peer identifier
     * @return the PeerConnectivity record for the peer
     */
    public PeerConnectivity getOrCreate(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        return peerMap.computeIfAbsent(peerId, PeerConnectivity::new);
    }

    /**
     * Looks up the PeerConnectivity for a peer.
     *
     * @param peerId the peer identifier
     * @return Optional containing the PeerConnectivity if registered, otherwise empty
     */
    public Optional<PeerConnectivity> lookup(PeerId peerId) {
        if (peerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(peerMap.get(peerId));
    }

    /**
     * Resolves a peer by looking up which peer owns an active path with the given connection ID.
     *
     * @param connectionId the active connection ID
     * @return Optional containing the matching PeerConnectivity
     */
    public Optional<PeerConnectivity> lookupByConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        return peerMap.values().stream()
                .filter(conn -> conn.allPaths().stream()
                        .anyMatch(path -> path.isActive() && connectionId.equals(path.connectionId())))
                .findFirst();
    }

    /**
     * Registers a specific path for a peer.
     *
     * @param peerId the peer identifier
     * @param path   the path to associate with this peer
     */
    public void registerPath(PeerId peerId, ConnectivityPath path) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        getOrCreate(peerId).addPath(path);
    }

    /**
     * Removes a peer's connectivity mapping entirely.
     *
     * @param peerId the peer identifier to remove
     * @return Optional containing the removed PeerConnectivity if it existed
     */
    public Optional<PeerConnectivity> removePeer(PeerId peerId) {
        if (peerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(peerMap.remove(peerId));
    }

    /**
     * Checks if a peer connectivity entry exists.
     */
    public boolean contains(PeerId peerId) {
        if (peerId == null) {
            return false;
        }
        return peerMap.containsKey(peerId);
    }

    /**
     * Returns an unmodifiable snapshot of all mapped peer connectivities.
     */
    public Collection<PeerConnectivity> allConnectivities() {
        return Collections.unmodifiableCollection(peerMap.values());
    }

    /**
     * Returns the total number of registered peers in this registry.
     */
    public int size() {
        return peerMap.size();
    }

    /**
     * Clears all peer connectivities.
     */
    public void clear() {
        peerMap.clear();
    }
}
