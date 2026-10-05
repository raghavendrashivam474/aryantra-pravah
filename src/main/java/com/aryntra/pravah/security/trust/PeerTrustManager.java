package com.aryntra.pravah.security.trust;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.authentication.AuthenticationChallenge;
import com.aryntra.pravah.security.authentication.AuthenticationProof;
import com.aryntra.pravah.security.authentication.AuthenticationResult;
import com.aryntra.pravah.security.authentication.AuthenticationService;
import com.aryntra.pravah.security.identity.CryptographicIdentity;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Authoritative manager for peer-level trust records and authentication handshake lifecycle.
 *
 * <p>Invariants:</p>
 * <ul>
 *   <li>Trust is peer-level, independent of physical transport paths.</li>
 *   <li>State transitions strictly follow: UNKNOWN ──► AUTHENTICATING ──► AUTHENTICATED ──► TRUSTED (or REJECTED).</li>
 *   <li>Only peers in {@link TrustState#TRUSTED} state are eligible for application message routing.</li>
 *   <li>Single-use challenges prevent replay attacks across sessions.</li>
 * </ul>
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
public final class PeerTrustManager {

    private static final Logger LOGGER = Logger.getLogger(PeerTrustManager.class.getName());

    private final AuthenticationService authService;
    private final TrustPolicy trustPolicy;
    private final ConcurrentHashMap<PeerId, PeerTrustRecord> trustRecords = new ConcurrentHashMap<>();
    private final List<TrustStateListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Constructs a PeerTrustManager with standard authentication service and default permissive trust policy.
     */
    public PeerTrustManager() {
        this(new AuthenticationService(), TrustPolicy.allowAllAuthenticated());
    }

    /**
     * Constructs a PeerTrustManager with custom authentication service and trust policy.
     */
    public PeerTrustManager(AuthenticationService authService, TrustPolicy trustPolicy) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.trustPolicy = Objects.requireNonNull(trustPolicy, "trustPolicy must not be null");
    }

    /**
     * Returns the underlying authentication service.
     */
    public AuthenticationService authService() {
        return authService;
    }

    /**
     * Returns the current trust state of a peer. Defaults to {@link TrustState#UNKNOWN}.
     */
    public TrustState getTrustState(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        PeerTrustRecord record = trustRecords.get(peerId);
        return record != null ? record.trustState() : TrustState.UNKNOWN;
    }

    /**
     * Returns {@code true} if the peer has completed authentication and passed trust policy.
     */
    public boolean isPeerTrusted(PeerId peerId) {
        return getTrustState(peerId) == TrustState.TRUSTED;
    }

    /**
     * Gets the full immutable {@link PeerTrustRecord} for a peer.
     */
    public PeerTrustRecord getRecord(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        return trustRecords.computeIfAbsent(peerId, id -> new PeerTrustRecord(id, TrustState.UNKNOWN));
    }

    /**
     * Issues a fresh cryptographic challenge for a peer and transitions its trust state to {@link TrustState#AUTHENTICATING}.
     */
    public AuthenticationChallenge issueChallengeForPeer(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        AuthenticationChallenge challenge = authService.issueChallenge();
        transitionState(peerId, TrustState.AUTHENTICATING);
        LOGGER.info(() -> "Issued challenge " + challenge.challengeId() + " for peer " + peerId.value() + " (state -> AUTHENTICATING)");
        return challenge;
    }

    /**
     * Evaluates a received {@link AuthenticationProof}, updates the peer's trust state, and returns the result.
     *
     * @param peerId the peer presenting the proof
     * @param proof  the cryptographic proof of possession
     * @return the {@link AuthenticationResult}
     */
    public AuthenticationResult evaluateProof(PeerId peerId, AuthenticationProof proof) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        if (proof == null || !peerId.equals(proof.claimantIdentity().peerId())) {
            LOGGER.warning(() -> "Proof evaluation failed: PeerId mismatch or null proof for " + peerId.value());
            transitionState(peerId, TrustState.REJECTED);
            return AuthenticationResult.MALFORMED_INPUT;
        }

        AuthenticationResult result = authService.verifyProof(proof);
        if (result.isSuccess()) {
            transitionState(peerId, TrustState.AUTHENTICATED);
            boolean trusted = trustPolicy.evaluate(proof.claimantIdentity(), result);
            if (trusted) {
                transitionState(peerId, TrustState.TRUSTED);
                LOGGER.info(() -> "Peer " + peerId.value() + " successfully authenticated and TRUSTED");
            } else {
                transitionState(peerId, TrustState.REJECTED);
                LOGGER.warning(() -> "Peer " + peerId.value() + " authenticated but rejected by TrustPolicy");
            }
        } else {
            transitionState(peerId, TrustState.REJECTED);
            LOGGER.warning(() -> "Authentication proof failed for peer " + peerId.value() + ": " + result);
        }
        return result;
    }

    /**
     * Explicitly transitions a peer's state.
     */
    public void transitionState(PeerId peerId, TrustState newState) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(newState, "newState must not be null");

        PeerTrustRecord updated = trustRecords.compute(peerId, (id, current) -> {
            TrustState oldState = current != null ? current.trustState() : TrustState.UNKNOWN;
            if (oldState == newState) {
                return current != null ? current : new PeerTrustRecord(id, newState);
            }
            PeerTrustRecord next = new PeerTrustRecord(id, newState);
            notifyListeners(peerId, oldState, newState);
            return next;
        });
    }

    /**
     * Resets a peer's trust record to UNKNOWN (e.g., when the peer session is explicitly cleared).
     */
    public void resetPeer(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        PeerTrustRecord removed = trustRecords.remove(peerId);
        if (removed != null && removed.trustState() != TrustState.UNKNOWN) {
            notifyListeners(peerId, removed.trustState(), TrustState.UNKNOWN);
        }
    }

    /**
     * Adds a listener for trust state transitions.
     */
    public void addTrustStateListener(TrustStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a trust state listener.
     */
    public void removeTrustStateListener(TrustStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(PeerId peerId, TrustState oldState, TrustState newState) {
        for (TrustStateListener listener : listeners) {
            try {
                listener.onTrustStateChanged(peerId, oldState, newState);
            } catch (Exception ex) {
                LOGGER.warning("Exception in TrustStateListener: " + ex.getMessage());
            }
        }
    }
}
