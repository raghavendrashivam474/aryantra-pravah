package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityGenerator;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.3: Malformed Input and Robustness Tests")
class AuthenticationMalformedTest {

    private final IdentityGenerator generator = new IdentityGenerator();
    private AuthenticationService authService;
    private IdentityKeyPair keyPair;
    private CryptographicIdentity identity;

    @BeforeEach
    void setUp() {
        authService = new AuthenticationService();
        keyPair = generator.generate();
        identity = CryptographicIdentity.of(PeerId.generate(), keyPair.publicKey());
    }

    @Test
    @DisplayName("Null input to verifyProof returns MALFORMED_INPUT safely without exceptions")
    void testNullProofHandling() {
        assertEquals(AuthenticationResult.MALFORMED_INPUT, authService.verifyProof(null));
        assertEquals(AuthenticationResult.MALFORMED_INPUT, authService.verifyDirect(null, null));
    }

    @Test
    @DisplayName("Corrupted or flipped signature bits return INVALID_PROOF safely")
    void testCorruptedSignatureBits() {
        AuthenticationChallenge challenge = authService.issueChallenge();
        AuthenticationProof validProof = AuthenticationProof.generate(
                challenge,
                identity,
                keyPair,
                authService.domain()
        );

        byte[] corruptedSig = validProof.signatureBytes();
        corruptedSig[0] = (byte) (corruptedSig[0] ^ 0xFF); // Bit flip corruption

        AuthenticationProof corruptedProof = new AuthenticationProof(
                identity,
                challenge.challengeId(),
                corruptedSig
        );

        AuthenticationResult result = authService.verifyProof(corruptedProof);
        assertEquals(AuthenticationResult.INVALID_PROOF, result);
    }

    @Test
    @DisplayName("Truncated signature buffer fails safely")
    void testTruncatedSignature() {
        AuthenticationChallenge challenge = authService.issueChallenge();

        byte[] truncatedSig = new byte[16]; // Only 16 bytes instead of 64 bytes for Ed25519

        AuthenticationProof malformedProof = new AuthenticationProof(
                identity,
                challenge.challengeId(),
                truncatedSig
        );

        AuthenticationResult result = authService.verifyProof(malformedProof);
        assertEquals(AuthenticationResult.INVALID_PROOF, result);
    }

    @Test
    @DisplayName("Empty signature buffer fails safely")
    void testEmptySignature() {
        AuthenticationChallenge challenge = authService.issueChallenge();

        AuthenticationProof emptySigProof = new AuthenticationProof(
                identity,
                challenge.challengeId(),
                new byte[0]
        );

        AuthenticationResult result = authService.verifyProof(emptySigProof);
        assertEquals(AuthenticationResult.INVALID_PROOF, result);
    }
}