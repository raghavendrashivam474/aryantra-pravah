package com.aryntra.pravah.security.trust;

import com.aryntra.pravah.peer.PeerId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record associating a peer identity with its current trust lifecycle state.
 *
 * <p>Trust is a peer-level property, not a path-level property. A single peer may be
 * reachable over TCP, Bluetooth, or future transports, but trust describes <em>who</em>
 * the peer is, not <em>how</em> we reach it.</p>
 *
 * <p>State transitions:</p>
 * <pre>
 *   UNKNOWN ──► AUTHENTICATING ──► AUTHENTICATED ──► TRUSTED
 *                    │
 *                    └──► REJECTED
 * </pre>
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
public final class PeerTrustRecord {

    private final PeerId peerId;
    private final TrustState trustState;
    private final Instant lastStateChange;

    public PeerTrustRecord(PeerId peerId, TrustState trustState) {
        this(peerId, trustState, Instant.now());
    }

    public PeerTrustRecord(PeerId peerId, TrustState trustState, Instant lastStateChange) {
        this.peerId = Objects.requireNonNull(peerId, "peerId must not be null");
        this.trustState = Objects.requireNonNull(trustState, "trustState must not be null");
        this.lastStateChange = Objects.requireNonNull(lastStateChange, "lastStateChange must not be null");
    }

    public PeerId peerId() {
        return peerId;
    }

    public TrustState trustState() {
        return trustState;
    }

    public Instant lastStateChange() {
        return lastStateChange;
    }

    /**
     * Returns true if this peer is application-usable (authenticated and trust policy passed).
     */
    public boolean isApplicationUsable() {
        return trustState == TrustState.TRUSTED;
    }

    /**
     * Returns a new record with the given trust state and current timestamp.
     */
    public PeerTrustRecord withState(TrustState newState) {
        return new PeerTrustRecord(this.peerId, newState, Instant.now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerTrustRecord that = (PeerTrustRecord) o;
        return peerId.equals(that.peerId) && trustState == that.trustState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId, trustState);
    }

    @Override
    public String toString() {
        return "PeerTrustRecord{peerId=" + peerId.value() + ", trust=" + trustState + "}";
    }
}
