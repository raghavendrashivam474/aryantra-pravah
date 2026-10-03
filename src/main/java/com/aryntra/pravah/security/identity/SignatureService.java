package com.aryntra.pravah.security.identity;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Objects;

/**
 * Standard cryptographic signing and verification primitive for Pravaah identities.
 *
 * <p>Uses the standard Ed25519 signature algorithm (RFC 8032) available in Java 15+.
 * This service operates as a standalone security primitive and is not coupled
 * to any network transport or protocol encoding.</p>
 *
 * <p>SX.2 — Cryptographic Identity Foundation</p>
 */
public final class SignatureService {

    public static final String SIGNATURE_ALGORITHM = "Ed25519";

    private SignatureService() {
        // Utility class
    }

    /**
     * Signs a raw byte payload using an {@link IdentityKeyPair}.
     *
     * @param keyPair the identity key pair containing the private key
     * @param payload the data to sign
     * @return the raw 64-byte Ed25519 signature
     * @throws NullPointerException if {@code keyPair} or {@code payload} is null
     */
    public static byte[] sign(IdentityKeyPair keyPair, byte[] payload) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        return sign(keyPair.privateKey(), payload);
    }

    /**
     * Signs a raw byte payload using a private key.
     *
     * @param privateKey the private key
     * @param payload the data to sign
     * @return the raw Ed25519 signature bytes
     * @throws NullPointerException if {@code privateKey} or {@code payload} is null
     * @throws IllegalArgumentException if the private key algorithm is invalid
     */
    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        Objects.requireNonNull(privateKey, "privateKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 signature algorithm unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid private key for Ed25519 signing", e);
        } catch (SignatureException e) {
            throw new IllegalStateException("Failed to generate digital signature", e);
        }
    }

    /**
     * Verifies a digital signature against a {@link CryptographicIdentity}.
     *
     * @param identity the claimed cryptographic identity
     * @param payload the data that was signed
     * @param signatureBytes the signature to verify
     * @return {@code true} if the signature is valid; {@code false} otherwise
     * @throws NullPointerException if any argument is null
     */
    public static boolean verify(CryptographicIdentity identity, byte[] payload, byte[] signatureBytes) {
        Objects.requireNonNull(identity, "identity must not be null");
        return verify(identity.publicKey(), payload, signatureBytes);
    }

    /**
     * Verifies a digital signature against a {@link PublicKey}.
     *
     * @param publicKey the public key corresponding to the signer
     * @param payload the data that was signed
     * @param signatureBytes the signature to verify
     * @return {@code true} if the signature is valid; {@code false} if invalid or malformed
     * @throws NullPointerException if any argument is null
     */
    public static boolean verify(PublicKey publicKey, byte[] payload, byte[] signatureBytes) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(signatureBytes, "signatureBytes must not be null");

        if (signatureBytes.length == 0) {
            return false;
        }

        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 signature algorithm unavailable", e);
        } catch (InvalidKeyException | SignatureException e) {
            // Malformed keys or malformed signature buffers fail verification safely
            return false;
        }
    }
}