package com.aryntra.pravah.security.identity;

import com.aryntra.pravah.peer.PeerId;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable cryptographic identity binding a logical {@link PeerId} to a public key.
 *
 * <p>This structure represents the public, shareable half of a peer's identity.
 * It contains no private key material and can safely be passed across trust boundaries,
 * published in discovery, or stored in peer registries.</p>
 *
 * <p>The conceptual model is:
 * <pre>
 *   Peer Identity
 *        ├── PeerId (logical addressing)
 *        ├── PublicKey (cryptographic anchor)
 *        └── Algorithm Metadata ("Ed25519")
 * </pre>
 * </p>
 *
 * <p>SX.2 — Cryptographic Identity Foundation</p>
 */
public final class CryptographicIdentity {

    public static final String DEFAULT_ALGORITHM = "Ed25519";

    private final PeerId peerId;
    private final PublicKey publicKey;
    private final String algorithm;
    private final byte[] canonicalPublicKeyBytes;

    private CryptographicIdentity(PeerId peerId, PublicKey publicKey, String algorithm) {
        this.peerId = Objects.requireNonNull(peerId, "peerId must not be null");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey must not be null");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
        if (algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be blank");
        }
        // Cache canonical bytes for fast deterministic equality and hashing
        this.canonicalPublicKeyBytes = PublicKeyCodec.encode(publicKey);
    }

    /**
     * Binds a {@link PeerId} and a {@link PublicKey} under the default Ed25519 algorithm.
     *
     * @param peerId the logical peer identifier
     * @param publicKey the public key
     * @return the bound {@link CryptographicIdentity}
     */
    public static CryptographicIdentity of(PeerId peerId, PublicKey publicKey) {
        return new CryptographicIdentity(peerId, publicKey, DEFAULT_ALGORITHM);
    }

    /**
     * Binds a {@link PeerId} and an {@link IdentityKeyPair}.
     *
     * @param peerId the logical peer identifier
     * @param keyPair the key pair whose public key will be bound
     * @return the bound {@link CryptographicIdentity}
     */
    public static CryptographicIdentity fromKeyPair(PeerId peerId, IdentityKeyPair keyPair) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        return new CryptographicIdentity(peerId, keyPair.publicKey(), keyPair.algorithm());
    }

    /** The logical peer identifier. */
    public PeerId peerId() {
        return peerId;
    }

    /** The public key. */
    public PublicKey publicKey() {
        return publicKey;
    }

    /** The cryptographic algorithm used (e.g., "Ed25519"). */
    public String algorithm() {
        return algorithm;
    }

    /**
     * Exports the public key in canonical DER format.
     *
     * @return a defensive copy of the encoded public key bytes
     */
    public byte[] encodedPublicKey() {
        return canonicalPublicKeyBytes.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CryptographicIdentity that = (CryptographicIdentity) o;
        return peerId.equals(that.peerId)
                && algorithm.equalsIgnoreCase(that.algorithm)
                && Arrays.equals(canonicalPublicKeyBytes, that.canonicalPublicKeyBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(peerId, algorithm.toLowerCase());
        result = 31 * result + Arrays.hashCode(canonicalPublicKeyBytes);
        return result;
    }

    @Override
    public String toString() {
        return "CryptographicIdentity[peerId=" + peerId.value()
                + ", algorithm=" + algorithm
                + ", keyFingerprint=" + Integer.toHexString(Arrays.hashCode(canonicalPublicKeyBytes))
                + "]";
    }
}