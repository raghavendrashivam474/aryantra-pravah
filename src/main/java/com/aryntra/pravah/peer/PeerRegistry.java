package com.aryntra.pravah.peer;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry for tracking known logical peers and their transport associations.
 *
 * <p><b>Registration Policy:</b> Registering a peer record whose {@link PeerId} is already registered
 * will update/replace the existing record. This allows dynamic updates when peers reconnect or re-route.</p>
 *
 * S3.2 - Phase 3: Peer Registry
 */
public class PeerRegistry {

    private final ConcurrentHashMap<PeerId, PeerRecord> peers = new ConcurrentHashMap<>();

    /**
     * Registers or updates a peer record.
     *
     * @param record the peer record to store (must not be null)
     * @return the previous record associated with the PeerId, or null if none
     */
    public PeerRecord register(PeerRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return peers.put(record.peerId(), record);
    }

    /**
     * Registers a peer with a specific transport connection ID.
     *
     * @param peerId the peer ID (must not be null)
     * @param connectionId the transport connection ID (must not be null)
     * @return the created and stored PeerRecord
     */
    public PeerRecord register(PeerId peerId, String connectionId) {
        PeerRecord record = PeerRecord.connected(peerId, connectionId);
        register(record);
        return record;
    }

    /**
     * Look up a peer by logical {@link PeerId}.
     *
     * @param peerId the peer ID to query
     * @return Optional containing the PeerRecord if found, otherwise empty
     */
    public Optional<PeerRecord> lookup(PeerId peerId) {
        if (peerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(peers.get(peerId));
    }

    /**
     * Look up a peer by active transport connection ID.
     *
     * @param connectionId the transport connection ID to query
     * @return Optional containing the first matching PeerRecord, otherwise empty
     */
    public Optional<PeerRecord> lookupByConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        return peers.values().stream()
                .filter(record -> connectionId.equals(record.connectionId()))
                .findFirst();
    }

    /**
     * Checks whether a peer is registered.
     *
     * @param peerId the peer ID to check
     * @return true if the peer is registered
     */
    public boolean contains(PeerId peerId) {
        if (peerId == null) {
            return false;
        }
        return peers.containsKey(peerId);
    }

    /**
     * Removes a peer from the registry.
     *
     * @param peerId the peer ID to remove
     * @return Optional containing the removed record if it existed, otherwise empty
     */
    public Optional<PeerRecord> remove(PeerId peerId) {
        if (peerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(peers.remove(peerId));
    }

    /**
     * Returns an unmodifiable snapshot collection of all registered peer records.
     */
    public Collection<PeerRecord> allPeers() {
        return Collections.unmodifiableCollection(peers.values());
    }

    /**
     * Returns the total number of registered peers.
     */
    public int size() {
        return peers.size();
    }

    /**
     * Clears all registered peers.
     */
    public void clear() {
        peers.clear();
    }
}