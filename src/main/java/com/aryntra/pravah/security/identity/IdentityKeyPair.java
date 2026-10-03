package com.aryntra.pravah.security.identity;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

/**
 * Immutable holder for an asymmetric key pair used as a Pravaah peer's
 * cryptographic identity material.
 *
 * <p>The private key is intentionally accessible only through an explicit
 * accessor to discourage accidental leakage into transport or protocol
 * layers. Callers should prefer passing {@link #publicKey()} outward
 * and keeping the private key confined to signing operations.</p>
 *
 * <p>SX.2 — Cryptographic Identity Foundation</p>
 */
public final class IdentityKeyPair {

    private static final String SUPPORTED_ALGORITHM = "Ed25519";
    private static final String SUPPORTED_ALGORITHM_ALT = "EdDSA";

    private final KeyPair keyPair;

    /**
     * Wraps an existing JCA {@link KeyPair}.
     *
     * @param keyPair a non-null Ed25519 key pair
     * @throws IllegalArgumentException if the algorithm is not Ed25519
     */
    public IdentityKeyPair(KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        Objects.requireNonNull(keyPair.getPublic(), "publicKey must not be null");
        Objects.requireNonNull(keyPair.getPrivate(), "privateKey must not be null");

        String algo = keyPair.getPublic().getAlgorithm();
        if (!SUPPORTED_ALGORITHM.equalsIgnoreCase(algo) && !SUPPORTED_ALGORITHM_ALT.equalsIgnoreCase(algo)) {
            throw new IllegalArgumentException(
                "Unsupported key algorithm: " + algo + " (expected " + SUPPORTED_ALGORITHM + ")");
        }
        this.keyPair = keyPair;
    }

    /** The algorithm name (always "Ed25519"). */
    public String algorithm() {
        return SUPPORTED_ALGORITHM;
    }

    /** The public half of the key pair — safe to share. */
    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    /**
     * The private half of the key pair — owner only.
     *
     * <p>This accessor exists so that {@link SignatureService} can sign
     * payloads. It must never be serialized, logged, or passed to
     * transport/protocol layers.</p>
     */
    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    @Override
    public String toString() {
        return "IdentityKeyPair[algorithm=" + SUPPORTED_ALGORITHM + ", publicKey="
            + publicKey().hashCode() + "]";
    }
}