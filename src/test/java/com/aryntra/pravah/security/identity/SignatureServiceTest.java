package com.aryntra.pravah.security.identity;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.2 — SignatureService Primitive")
class SignatureServiceTest {

    private final IdentityGenerator generator = new IdentityGenerator();

    @Test
    @DisplayName("sign() and verify() succeed with matching key pair")
    void validSignatureVerifiesSuccessfully() {
        IdentityKeyPair keyPair = generator.generate();
        byte[] payload = "Hello Pravaah Network".getBytes(StandardCharsets.UTF_8);

        byte[] sig = SignatureService.sign(keyPair, payload);
        assertNotNull(sig);
        assertEquals(64, sig.length, "Ed25519 raw signature must be 64 bytes");

        boolean isValid = SignatureService.verify(keyPair.publicKey(), payload, sig);
        assertTrue(isValid, "Valid signature must verify successfully");
    }

    @Test
    @DisplayName("verify() via CryptographicIdentity succeeds")
    void verifyViaCryptographicIdentity() {
        PeerId peerId = PeerId.of("alice-node");
        IdentityKeyPair keyPair = generator.generate();
        CryptographicIdentity identity = CryptographicIdentity.fromKeyPair(peerId, keyPair);
        byte[] payload = "Identity bound payload".getBytes(StandardCharsets.UTF_8);

        byte[] sig = SignatureService.sign(keyPair, payload);
        boolean isValid = SignatureService.verify(identity, payload, sig);

        assertTrue(isValid);
    }

    @Test
    @DisplayName("tampered payload fails verification")
    void tamperedPayloadFails() {
        IdentityKeyPair keyPair = generator.generate();
        byte[] originalPayload = "Original message content".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedPayload = "Tampered message content".getBytes(StandardCharsets.UTF_8);

        byte[] sig = SignatureService.sign(keyPair, originalPayload);

        boolean isValid = SignatureService.verify(keyPair.publicKey(), tamperedPayload, sig);
        assertFalse(isValid, "Altered message must fail signature verification");
    }

    @Test
    @DisplayName("tampered signature bytes fail verification safely")
    void tamperedSignatureFails() {
        IdentityKeyPair keyPair = generator.generate();
        byte[] payload = "Critical security handshake".getBytes(StandardCharsets.UTF_8);

        byte[] sig = SignatureService.sign(keyPair, payload);
        // Corrupt one bit
        byte[] tamperedSig = sig.clone();
        tamperedSig[0] ^= 0x01;

        boolean isValid = SignatureService.verify(keyPair.publicKey(), payload, tamperedSig);
        assertFalse(isValid, "Corrupted signature must fail verification");
    }

    @Test
    @DisplayName("verification with different peer public key fails")
    void wrongPublicKeyFails() {
        IdentityKeyPair keyPairA = generator.generate();
        IdentityKeyPair keyPairB = generator.generate();
        byte[] payload = "Signed by Peer A".getBytes(StandardCharsets.UTF_8);

        byte[] sig = SignatureService.sign(keyPairA, payload);

        // Verify using Peer B's public key
        boolean isValid = SignatureService.verify(keyPairB.publicKey(), payload, sig);
        assertFalse(isValid, "Signature from Key A must not verify against Key B");
    }

    @Test
    @DisplayName("sign and verify empty payload succeeds")
    void emptyPayloadSupported() {
        IdentityKeyPair keyPair = generator.generate();
        byte[] emptyPayload = new byte[0];

        byte[] sig = SignatureService.sign(keyPair, emptyPayload);
        assertNotNull(sig);

        boolean isValid = SignatureService.verify(keyPair.publicKey(), emptyPayload, sig);
        assertTrue(isValid, "Empty payload signature should verify correctly");
    }

    @Test
    @DisplayName("verification with empty or truncated signature returns false safely")
    void emptyOrTruncatedSignatureFailsSafely() {
        IdentityKeyPair keyPair = generator.generate();
        byte[] payload = "Some data".getBytes(StandardCharsets.UTF_8);

        assertFalse(SignatureService.verify(keyPair.publicKey(), payload, new byte[0]));
        assertFalse(SignatureService.verify(keyPair.publicKey(), payload, new byte[10]));
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullGuards() {
        IdentityKeyPair keyPair = generator.generate();
        byte[] payload = new byte[]{1, 2, 3};
        byte[] sig = SignatureService.sign(keyPair, payload);

        assertThrows(NullPointerException.class, () -> SignatureService.sign((IdentityKeyPair) null, payload));
        assertThrows(NullPointerException.class, () -> SignatureService.sign(keyPair, null));
        assertThrows(NullPointerException.class, () -> SignatureService.verify((CryptographicIdentity) null, payload, sig));
        assertThrows(NullPointerException.class, () -> SignatureService.verify(keyPair.publicKey(), null, sig));
        assertThrows(NullPointerException.class, () -> SignatureService.verify(keyPair.publicKey(), payload, null));
    }
}