package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates and manages the set of known communication paths for a single {@link PeerId}.
 *
 * <p>PeerConnectivity answers: <i>"How can Pravah reach Peer X?"</i> by tracking
 * multiple possible candidate, active, or inactive paths independently from any single
 * transport socket.</p>
 *
 * S6.1 - Phase 6: Connectivity Evolution
 */
public final class PeerConnectivity {
    private final PeerId peerId;
    private final Map<PathId, ConnectivityPath> paths = new LinkedHashMap<>();
    private final List<PathStateListener> listeners = new CopyOnWriteArrayList<>();

    public PeerConnectivity(PeerId peerId) {
        this.peerId = Objects.requireNonNull(peerId, "peerId must not be null");
    }

    public PeerId peerId() {
        return peerId;
    }

    /**
     * Adds or updates a path for this peer and notifies listeners of any state transition.
     *
     * @param path the path to add (must belong to this peer)
     * @throws IllegalArgumentException if the path does not belong to this peer
     */
    public synchronized void addPath(ConnectivityPath path) {
        Objects.requireNonNull(path, "path must not be null");
        if (!path.peerId().equals(this.peerId)) {
            throw new IllegalArgumentException(
                    "Path peerId [" + path.peerId() + "] does not match PeerConnectivity peerId [" + this.peerId + "]"
            );
        }
        ConnectivityPath oldPath = paths.get(path.pathId());
        PathState oldState = (oldPath != null) ? oldPath.state() : null;
        paths.put(path.pathId(), path);

        if (oldState != path.state()) {
            notifyListeners(path, oldState);
        }
    }

    /**
     * Removes a path by its {@link PathId}.
     *
     * @param pathId the identifier of the path to remove
     * @return Optional containing the removed path if present, otherwise empty
     */
    public synchronized Optional<ConnectivityPath> removePath(PathId pathId) {
        if (pathId == null) {
            return Optional.empty();
        }
        Optional<ConnectivityPath> removed = Optional.ofNullable(paths.remove(pathId));
        removed.ifPresent(path -> notifyListeners(path.deactivate(), path.state()));
        return removed;
    }

    /**
     * Looks up a path by its {@link PathId}.
     */
    public synchronized Optional<ConnectivityPath> findPath(PathId pathId) {
        if (pathId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(paths.get(pathId));
    }

    /**
     * Returns an unmodifiable snapshot of all paths known for this peer.
     */
    public synchronized Collection<ConnectivityPath> allPaths() {
        return List.copyOf(paths.values());
    }

    /**
     * Returns a list of currently active paths.
     */
    public synchronized List<ConnectivityPath> activePaths() {
        return paths.values().stream()
                .filter(ConnectivityPath::isActive)
                .toList();
    }

    /**
     * Returns a list of candidate paths that can potentially be connected.
     */
    public synchronized List<ConnectivityPath> candidatePaths() {
        return paths.values().stream()
                .filter(ConnectivityPath::isCandidate)
                .toList();
    }

    /**
     * Returns true if this peer has at least one active path.
     */
    public synchronized boolean hasActivePath() {
        return paths.values().stream().anyMatch(ConnectivityPath::isActive);
    }

    /**
     * Returns the total count of known paths (active, candidate, inactive).
     */
    public synchronized int pathCount() {
        return paths.size();
    }

    /**
     * Returns true if there are no known paths for this peer.
     */
    public synchronized boolean isEmpty() {
        return paths.isEmpty();
    }

    // --- Listener Management ---

    public void addPathStateListener(PathStateListener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removePathStateListener(PathStateListener l) {
        if (l != null) {
            listeners.remove(l);
        }
    }

    private void notifyListeners(ConnectivityPath path, PathState previousState) {
        for (PathStateListener l : listeners) {
            try {
                l.onPathStateChanged(this.peerId, path, previousState);
            } catch (Exception ex) {
                // Prevent downstream callback errors from disrupting connectivity state mutations
            }
        }
    }
}