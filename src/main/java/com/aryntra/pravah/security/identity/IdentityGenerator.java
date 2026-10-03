package com.aryntra.pravah.security.identity;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.util.Objects;

/**
 * Generates Ed25519 identity key pairs for Pravaah peers.
 *
 * <p>Uses the JDK's built-in Ed25519 provider (available since Java 15)
 * and {@link SecureRandom} for cryptographic randomness. No external
 * cryptographic libraries are required.</p>
 *
 * <p>SX.2 — Cryptographic Identity Foundation</p>
 */
public final class IdentityGenerator {

    public static final String ALGORITHM = "Ed25519";

    private final SecureRandom secureRandom;

    /**
     * Creates a generator using the platform-default SecureRandom.
     *
     * @throws IllegalStateException if Ed25519 is not available in the JDK
     */
    public IdentityGenerator() {
        this(new SecureRandom());
    }

    /**
     * Creates a generator with an explicit SecureRandom source.
     *
     * @param secureRandom the randomness source
     */
    public IdentityGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        // Verify algorithm availability early
        try {
            KeyPairGenerator.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "Ed25519 not available in this JDK runtime.", e);
        }
    }

    /**
     * Generates a new Ed25519 identity key pair.
     *
     * @return a fresh {@link IdentityKeyPair}
     */
    public IdentityKeyPair generate() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            kpg.initialize(NamedParameterSpec.ED25519, secureRandom);
            KeyPair kp = kpg.generateKeyPair();
            return new IdentityKeyPair(kp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate Ed25519 key pair", e);
        }
    }
}