package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.peer.PeerId;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe presence coordinator tracking online/offline states, metadata,
 * and executing periodic TTL-based presence eviction.
 *
 * S3.5 - Phase 3: Peer Presence
 */
public final class PeerPresenceManager {

    private static final Logger logger = Logger.getLogger(PeerPresenceManager.class.getName());

    private final ConcurrentHashMap<PeerId, PeerPresence> presenceMap = new ConcurrentHashMap<>();
    private final List<PeerPresenceListener> listeners = new CopyOnWriteArrayList<>();
    private final long presenceTtlMs;

    private ScheduledExecutorService expiryScheduler;
    private final Object stateLock = new Object();
    private boolean running = false;

    /**
     * @param presenceTtlMs duration of silence before an AVAILABLE peer becomes UNAVAILABLE
     */
    public PeerPresenceManager(long presenceTtlMs) {
        if (presenceTtlMs <= 0) {
            throw new IllegalArgumentException("presenceTtlMs must be positive");
        }
        this.presenceTtlMs = presenceTtlMs;
    }

    public void registerListener(PeerPresenceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void unregisterListener(PeerPresenceListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void start() {
        synchronized (stateLock) {
            if (running) return;
            running = true;

            expiryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pravah-presence-expiry");
                t.setDaemon(true);
                return t;
            });

            // Periodically check for silent peers (half the TTL resolution)
            long checkInterval = Math.max(100, presenceTtlMs / 2);
            expiryScheduler.scheduleAtFixedRate(this::checkExpiry, checkInterval, checkInterval, TimeUnit.MILLISECONDS);
            logger.info("PeerPresenceManager started with TTL: " + presenceTtlMs + "ms");
        }
    }

    public void stop() {
        synchronized (stateLock) {
            if (!running) return;
            running = false;

            if (expiryScheduler != null) {
                expiryScheduler.shutdownNow();
                try {
                    if (!expiryScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                        logger.warning("Presence expiry scheduler did not stop cleanly.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                expiryScheduler = null;
            }
            logger.info("PeerPresenceManager stopped.");
        }
    }

    public PeerPresence getPresence(PeerId peerId) {
        return presenceMap.getOrDefault(peerId, PeerPresence.unknown(peerId));
    }

    public Collection<PeerPresence> allPresences() {
        return Collections.unmodifiableCollection(presenceMap.values());
    }

    /**
     * Process a LAN peer discovery observation.
     */
    public void reportDiscovered(PeerId peerId, String host, int port) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(host, "host must not be null");

        presenceMap.compute(peerId, (id, current) -> {
            Instant now = Instant.now();
            PeerPresenceState nextState = PeerPresenceState.AVAILABLE;

            if (current != null && current.state() == PeerPresenceState.CONNECTED) {
                // If currently connected, discovery updates metadata/lastSeen but keeps CONNECTED state
                nextState = PeerPresenceState.CONNECTED;
            }

            PeerPresence updated = new PeerPresence(id, nextState, host, port, now);
            notifyListenersIfChanged(current, updated);
            return updated;
        });
    }

    /**
     * Process an active network connection event.
     */
    public void reportConnected(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");

        presenceMap.compute(peerId, (id, current) -> {
            Instant now = Instant.now();
            String host = (current != null) ? current.hostAddress() : "unknown";
            int port = (current != null) ? current.port() : 0;

            PeerPresence updated = new PeerPresence(id, PeerPresenceState.CONNECTED, host, port, now);
            notifyListenersIfChanged(current, updated);
            return updated;
        });
    }

    /**
     * Process a network disconnection event.
     */
    public void reportDisconnected(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");

        presenceMap.compute(peerId, (id, current) -> {
            if (current == null || current.state() != PeerPresenceState.CONNECTED) {
                return current; // No-op if wasn't connected
            }

            Instant now = Instant.now();
            boolean stillWithinTtl = now.toEpochMilli() - current.lastSeen().toEpochMilli() < presenceTtlMs;
            PeerPresenceState nextState = stillWithinTtl ? PeerPresenceState.AVAILABLE : PeerPresenceState.UNAVAILABLE;

            PeerPresence updated = new PeerPresence(id, nextState, current.hostAddress(), current.port(), current.lastSeen());
            notifyListenersIfChanged(current, updated);
            return updated;
        });
    }

    private void checkExpiry() {
        Instant threshold = Instant.now().minusMillis(presenceTtlMs);

        for (PeerId id : presenceMap.keySet()) {
            presenceMap.computeIfPresent(id, (peerId, current) -> {
                if (current.state() == PeerPresenceState.AVAILABLE && current.lastSeen().isBefore(threshold)) {
                    PeerPresence expired = new PeerPresence(
                            peerId,
                            PeerPresenceState.UNAVAILABLE,
                            current.hostAddress(),
                            current.port(),
                            current.lastSeen()
                    );
                    notifyListenersIfChanged(current, expired);
                    return expired;
                }
                return current;
            });
        }
    }

    private void notifyListenersIfChanged(PeerPresence current, PeerPresence updated) {
        PeerPresence safeCurrent = (current != null) ? current : PeerPresence.unknown(updated.peerId());
        if (safeCurrent.state() != updated.state()) {
            for (PeerPresenceListener listener : listeners) {
                try {
                    listener.onPresenceChanged(updated.peerId(), safeCurrent, updated);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error executing presence change listener", e);
                }
            }
        }
    }
}