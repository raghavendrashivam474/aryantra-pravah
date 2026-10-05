package com.aryntra.pravah.security.identity;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

/**
 * Immutable holder for an asymmetric key pair used as a Pravaah peer's
 * cryptographic identity material.
 *
 * SX.2 — Cryptographic Identity Foundation
 */
public final class IdentityKeyPair {

    private final KeyPair keyPair;
    private final String algorithm;

    public IdentityKeyPair(KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        Objects.requireNonNull(keyPair.getPublic(), "publicKey must not be null");
        Objects.requireNonNull(keyPair.getPrivate(), "privateKey must not be null");
        this.keyPair = keyPair;
        String rawAlgo = keyPair.getPublic().getAlgorithm();
        if ("EdDSA".equalsIgnoreCase(rawAlgo) || "Ed25519".equalsIgnoreCase(rawAlgo)) {
            this.algorithm = "Ed25519";
        } else {
            this.algorithm = rawAlgo;
        }
    }

    public String algorithm() {
        return algorithm;
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    @Override
    public String toString() {
        return "IdentityKeyPair[algorithm=" + algorithm + ", publicKey="
            + publicKey().hashCode() + "]";
    }
}