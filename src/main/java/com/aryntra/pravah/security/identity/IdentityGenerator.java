package com.aryntra.pravah.security.identity;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Generates cryptographic identity key pairs for Pravaah peers.
 * Supports standard Ed25519 where available, with automatic fallback to EC (secp256r1)
 * on Android platforms (API 26-32) where Ed25519 JCA providers are not bundled.
 *
 * SX.2 — Cryptographic Identity Foundation
 */
public final class IdentityGenerator {

    private static final Logger LOGGER = Logger.getLogger(IdentityGenerator.class.getName());
    public static final String ALGORITHM = "Ed25519";
    private final SecureRandom secureRandom;

    public IdentityGenerator() {
        this(new SecureRandom());
    }

    public IdentityGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public IdentityKeyPair generate() {
        // Attempt 1: Standard Ed25519 (Java 15+ / Android 33+)
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            try {
                Class<?> specClass = Class.forName("java.security.spec.NamedParameterSpec");
                Object ed25519Spec = specClass.getField("ED25519").get(null);
                kpg.initialize((java.security.spec.AlgorithmParameterSpec) ed25519Spec, secureRandom);
            } catch (Throwable t) {
                // If NamedParameterSpec is not available on older Android, initialize with keysize 256
                kpg.initialize(256, secureRandom);
            }
            KeyPair kp = kpg.generateKeyPair();
            return new IdentityKeyPair(kp);
        } catch (Throwable t) {
            LOGGER.fine("Ed25519 unavailable on this runtime, falling back to EC: " + t.getMessage());
        }

        // Attempt 2: Standard Android EC key pair generator (secp256r1)
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256, secureRandom);
            KeyPair kp = kpg.generateKeyPair();
            return new IdentityKeyPair(kp);
        } catch (Throwable t) {
            LOGGER.warning("EC key generator unavailable, falling back to RSA: " + t.getMessage());
        }

        // Attempt 3: Standard RSA 2048 fallback (Universal JCE)
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, secureRandom);
            KeyPair kp = kpg.generateKeyPair();
            return new IdentityKeyPair(kp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize any cryptographic KeyPairGenerator on platform", e);
        }
    }
}