package com.aryntra.pravah.security.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.2 — Identity Key Generation")
class IdentityGeneratorTest {

    @Test
    @DisplayName("generate() produces a valid Ed25519 key pair")
    void generatesValidKeyPair() {
        IdentityGenerator gen = new IdentityGenerator();
        IdentityKeyPair kp = gen.generate();

        assertNotNull(kp);
        assertEquals("Ed25519", kp.algorithm());
        assertNotNull(kp.publicKey());
        assertNotNull(kp.privateKey());
        assertTrue(
            "Ed25519".equalsIgnoreCase(kp.publicKey().getAlgorithm()) ||
            "EdDSA".equalsIgnoreCase(kp.publicKey().getAlgorithm())
        );
    }

    @Test
    @DisplayName("two generated key pairs have different public keys")
    void generatesUniqueKeyPairs() {
        IdentityGenerator gen = new IdentityGenerator();
        IdentityKeyPair a = gen.generate();
        IdentityKeyPair b = gen.generate();

        assertFalse(
            Arrays.equals(a.publicKey().getEncoded(), b.publicKey().getEncoded()),
            "Two generated identities must have distinct public keys"
        );
    }

    @Test
    @DisplayName("public key encoding is non-empty and reasonable size")
    void publicKeyHasExpectedSize() {
        IdentityKeyPair kp = new IdentityGenerator().generate();
        byte[] encoded = kp.publicKey().getEncoded();

        assertNotNull(encoded);
        // Ed25519 public key in X.509 encoding is 44 bytes in standard JCA
        assertTrue(encoded.length >= 32, "Public key encoding too short");
        assertTrue(encoded.length <= 64, "Public key encoding unexpectedly large");
    }

    @Test
    @DisplayName("private key is not accidentally equal to public key")
    void privateAndPublicKeysDiffer() {
        IdentityKeyPair kp = new IdentityGenerator().generate();

        assertFalse(
            Arrays.equals(kp.publicKey().getEncoded(), kp.privateKey().getEncoded()),
            "Private and public key material must differ"
        );
    }

    @Test
    @DisplayName("toString does not leak private key material")
    void toStringDoesNotLeakPrivateKey() {
        IdentityKeyPair kp = new IdentityGenerator().generate();
        String s = kp.toString();

        assertTrue(s.contains("Ed25519"));
        assertFalse(s.contains("PrivateKey"), "toString should not expose private key details");
    }

    @Test
    @DisplayName("generator rejects null SecureRandom")
    void rejectsNullSecureRandom() {
        assertThrows(NullPointerException.class, () -> new IdentityGenerator(null));
    }
}