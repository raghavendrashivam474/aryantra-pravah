package com.aryntra.pravah.security.identity;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

/**
 * Deterministic serializer and deserializer for Pravaah public identity keys.
 *
 * <p>Uses standard JCA X.509 SubjectPublicKeyInfo DER encoding to represent
 * Ed25519 public keys in a transport-independent, cross-platform wire format.</p>
 *
 * <p>SX.2 — Cryptographic Identity Foundation</p>
 */
public final class PublicKeyCodec {

    private static final String ALGORITHM = "Ed25519";

    private PublicKeyCodec() {
        // Utility class
    }

    /**
     * Serializes a public key to its canonical standard binary representation.
     *
     * @param publicKey the public key to encode
     * @return the DER-encoded SubjectPublicKeyInfo byte array
     * @throws NullPointerException if {@code publicKey} is null
     * @throws IllegalArgumentException if the key algorithm is not supported
     */
    public static byte[] encode(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        String algo = publicKey.getAlgorithm();
        if (!ALGORITHM.equalsIgnoreCase(algo) && !"EdDSA".equalsIgnoreCase(algo)) {
            throw new IllegalArgumentException(
                "Unsupported key algorithm: " + algo + " (expected " + ALGORITHM + ")");
        }
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("PublicKey returned empty or null encoding");
        }
        return encoded.clone();
    }

    /**
     * Reconstructs an Ed25519 {@link PublicKey} from standard binary encoding.
     *
     * @param encodedBytes the DER-encoded SubjectPublicKeyInfo byte array
     * @return the reconstructed {@link PublicKey}
     * @throws NullPointerException if {@code encodedBytes} is null
     * @throws IllegalArgumentException if {@code encodedBytes} is malformed, truncated, or invalid
     */
    public static PublicKey decode(byte[] encodedBytes) {
        Objects.requireNonNull(encodedBytes, "encodedBytes must not be null");
        if (encodedBytes.length == 0) {
            throw new IllegalArgumentException("Encoded public key bytes must not be empty");
        }

        try {
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedBytes);
            return kf.generatePublic(spec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 algorithm not available in this JDK runtime", e);
        } catch (InvalidKeySpecException | RuntimeException e) {
            throw new IllegalArgumentException("Malformed or invalid Ed25519 public key encoding", e);
        }
    }
}