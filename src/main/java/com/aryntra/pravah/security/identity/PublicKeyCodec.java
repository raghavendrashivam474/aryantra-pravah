package com.aryntra.pravah.security.identity;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

/**
 * Deterministic serializer and deserializer for Pravaah public identity keys.
 * SX.2 — Cryptographic Identity Foundation
 */
public final class PublicKeyCodec {

    private PublicKeyCodec() {}

    public static byte[] encode(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("PublicKey returned empty or null encoding");
        }
        return encoded.clone();
    }

    public static PublicKey decode(byte[] encodedBytes) {
        return decode(encodedBytes, "Ed25519");
    }

    public static PublicKey decode(byte[] encodedBytes, String algorithm) {
        Objects.requireNonNull(encodedBytes, "encodedBytes must not be null");
        if (encodedBytes.length == 0) {
            throw new IllegalArgumentException("Encoded public key bytes must not be empty");
        }
        String[] algorithmsToTry = new String[] { algorithm, "Ed25519", "EdDSA", "EC", "RSA" };
        for (String algo : algorithmsToTry) {
            try {
                KeyFactory kf = KeyFactory.getInstance(algo);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedBytes);
                return kf.generatePublic(spec);
            } catch (Exception ignored) {}
        }
        throw new IllegalArgumentException("Unable to decode public key with any supported algorithm");
    }
}