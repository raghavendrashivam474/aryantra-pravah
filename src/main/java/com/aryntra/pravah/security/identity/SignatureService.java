package com.aryntra.pravah.security.identity;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Objects;

/**
 * Standard cryptographic signing and verification primitive for Pravaah identities.
 * SX.2 — Cryptographic Identity Foundation
 */
public final class SignatureService {

    private SignatureService() {}

    public static byte[] sign(IdentityKeyPair keyPair, byte[] payload) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        return sign(keyPair.privateKey(), payload);
    }

    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        Objects.requireNonNull(privateKey, "privateKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            String algo = resolveSignatureAlgorithm(privateKey.getAlgorithm());
            Signature signature = Signature.getInstance(algo);
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid private key for signing", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate digital signature", e);
        }
    }

    public static boolean verify(CryptographicIdentity identity, byte[] payload, byte[] signatureBytes) {
        Objects.requireNonNull(identity, "identity must not be null");
        return verify(identity.publicKey(), payload, signatureBytes);
    }

    public static boolean verify(PublicKey publicKey, byte[] payload, byte[] signatureBytes) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(signatureBytes, "signatureBytes must not be null");
        if (signatureBytes.length == 0) {
            return false;
        }
        try {
            String algo = resolveSignatureAlgorithm(publicKey.getAlgorithm());
            Signature signature = Signature.getInstance(algo);
            signature.initVerify(publicKey);
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveSignatureAlgorithm(String keyAlgorithm) {
        if ("Ed25519".equalsIgnoreCase(keyAlgorithm) || "EdDSA".equalsIgnoreCase(keyAlgorithm)) {
            return "Ed25519";
        } else if ("EC".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withECDSA";
        } else if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withRSA";
        }
        return "SHA256withECDSA";
    }
}