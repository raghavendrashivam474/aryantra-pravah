package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.SignatureService;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verification service for Pravaah Cryptographic Proof of Possession handshakes.
 *
 * <p>Acts as the verifier boundary:
 * <ul>
 *   <li>Issues fresh, unpredictable challenges with strict validity lifecycles.</li>
 *   <li>Verifies claimant identity signatures against reconstructed canonical context.</li>
 *   <li>Enforces single-use challenge consumption to guarantee replay resistance.</li>
 *   <li>Maintains fail-closed semantics across all potential failure modes.</li>
 * </ul>
 * </p>
 *
 * <p>SX.3 — Authentication Handshake & Proof of Possession</p>
 */
public final class AuthenticationService {
    private static final Logger LOGGER = Logger.getLogger(AuthenticationService.class.getName());
    private static final String SUPPORTED_ALGORITHM = "Ed25519";
    private static final String SUPPORTED_ALGORITHM_ALT = "EdDSA";

    private final String domain;
    private final Map<String, AuthenticationChallenge> pendingChallenges = new ConcurrentHashMap<>();

    /**
     * Creates an authentication service using the default security domain.
     */
    public AuthenticationService() {
        this(AuthenticationProof.DEFAULT_DOMAIN);
    }

    /**
     * Creates an authentication service bound to an explicit domain identifier.
     *
     * @param domain the domain context string
     */
    public AuthenticationService(String domain) {
        this.domain = Objects.requireNonNull(domain, "domain must not be null");
        if (domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
    }

    /** The security domain bound to this service. */
    public String domain() {
        return domain;
    }

    /**
     * Issues and registers a fresh challenge for an authenticating peer.
     *
     * @return the generated {@link AuthenticationChallenge}
     */
    public AuthenticationChallenge issueChallenge() {
        pruneExpiredChallenges();
        AuthenticationChallenge challenge = AuthenticationChallenge.generate();
        pendingChallenges.put(challenge.challengeId(), challenge);
        return challenge;
    }

    /**
     * Registers an externally generated or mock challenge (primarily for testing or specialized orchestration).
     *
     * @param challenge the challenge to register
     */
    public void registerChallenge(AuthenticationChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge must not be null");
        pendingChallenges.put(challenge.challengeId(), challenge);
    }

    /**
     * Verifies a presented {@link AuthenticationProof} against an active, registered challenge.
     *
     * <p>Consumes the challenge upon attempt to prevent replay attacks regardless of proof validity.</p>
     *
     * @param proof the proof submitted by the claimant
     * @return the {@link AuthenticationResult} code
     */
    public AuthenticationResult verifyProof(AuthenticationProof proof) {
        if (proof == null || proof.claimantIdentity() == null || proof.challengeId() == null || proof.signatureBytes() == null) {
            return AuthenticationResult.MALFORMED_INPUT;
        }

        AuthenticationChallenge challenge = pendingChallenges.remove(proof.challengeId());
        if (challenge == null) {
            return AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED;
        }

        return verifyDirect(challenge, proof);
    }

    /**
     * Directly verifies an {@link AuthenticationProof} against a specific {@link AuthenticationChallenge}
     * without requiring prior registration in the internal map.
     *
     * <p>Atomically consumes the challenge before cryptographic verification.</p>
     *
     * @param challenge the challenge issued to the claimant
     * @param proof     the proof submitted by the claimant
     * @return the {@link AuthenticationResult} code
     */
    public AuthenticationResult verifyDirect(AuthenticationChallenge challenge, AuthenticationProof proof) {
        if (challenge == null || proof == null || proof.claimantIdentity() == null || proof.challengeId() == null || proof.signatureBytes() == null) {
            return AuthenticationResult.MALFORMED_INPUT;
        }

        // Single-use challenge consumption check
        if (!challenge.consume() || challenge.isExpired()) {
            return AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED;
        }

        // Challenge ID cross-check
        if (!challenge.challengeId().equals(proof.challengeId())) {
            return AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED;
        }

        CryptographicIdentity identity = proof.claimantIdentity();

        // Algorithm validation
        String algo = identity.algorithm();
        if (!SUPPORTED_ALGORITHM.equalsIgnoreCase(algo) && !SUPPORTED_ALGORITHM_ALT.equalsIgnoreCase(algo)) {
            return AuthenticationResult.UNSUPPORTED_ALGORITHM;
        }

        // Reconstruct canonical authentication context bytes
        byte[] canonicalContext;
        try {
            canonicalContext = AuthenticationProof.constructCanonicalContext(
                    this.domain,
                    challenge.challengeId(),
                    challenge.challengeBytes(),
                    identity.peerId().value(),
                    identity.encodedPublicKey()
            );
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Failed to reconstruct canonical context", ex);
            return AuthenticationResult.MALFORMED_INPUT;
        }

        // Cryptographic signature verification via SX.2 primitive
        try {
            boolean valid = SignatureService.verify(identity, canonicalContext, proof.signatureBytes());
            if (valid) {
                return AuthenticationResult.SUCCESS;
            } else {
                return AuthenticationResult.INVALID_PROOF;
            }
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Cryptographic verification threw exception", ex);
            return AuthenticationResult.INVALID_PROOF;
        }
    }

    /**
     * Prunes expired challenges from memory to prevent unbounded heap growth.
     */
    public void pruneExpiredChallenges() {
        pendingChallenges.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Returns the count of currently pending challenges awaiting proof.
     */
    public int pendingChallengeCount() {
        return pendingChallenges.size();
    }
}