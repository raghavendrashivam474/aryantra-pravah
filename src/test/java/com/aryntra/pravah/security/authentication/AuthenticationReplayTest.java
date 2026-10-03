package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityGenerator;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.3: Replay Resistance & Challenge Lifecycle Tests")
class AuthenticationReplayTest {

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
    @DisplayName("Replay attack: Resubmitting identical proof fails second time")
    void testProofReplayFails() {
        AuthenticationChallenge challenge = authService.issueChallenge();

        AuthenticationProof proof = AuthenticationProof.generate(
                challenge,
                identity,
                keyPair,
                authService.domain()
        );

        // First verification succeeds
        AuthenticationResult firstAttempt = authService.verifyProof(proof);
        assertEquals(AuthenticationResult.SUCCESS, firstAttempt);

        // Replay attempt against the service must fail because challenge was consumed/removed
        AuthenticationResult replayAttempt = authService.verifyProof(proof);
        assertEquals(AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED, replayAttempt);
        assertFalse(replayAttempt.isSuccess());
    }

    @Test
    @DisplayName("Challenge reuse: Captured signature cannot satisfy a new challenge")
    void testCapturedProofCannotSatisfyNewChallenge() {
        // Peer receives Challenge A and creates Proof A
        AuthenticationChallenge challengeA = authService.issueChallenge();
        AuthenticationProof proofA = AuthenticationProof.generate(
                challengeA,
                identity,
                keyPair,
                authService.domain()
        );

        // An eavesdropper captures Proof A and waits for verifier to issue Challenge B
        AuthenticationChallenge challengeB = authService.issueChallenge();

        // Attacker creates a forged proof container putting Proof A's signature against Challenge B's ID
        AuthenticationProof replayedProof = new AuthenticationProof(
                identity,
                challengeB.challengeId(),
                proofA.signatureBytes() // Reusing signature generated for Challenge A
        );

        // Verifying replayed proof against Challenge B must fail cryptographic check
        AuthenticationResult result = authService.verifyProof(replayedProof);

        assertEquals(AuthenticationResult.INVALID_PROOF, result);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Direct verification replay: Single challenge instance cannot be verified twice")
    void testDirectVerificationSingleUse() {
        AuthenticationChallenge challenge = AuthenticationChallenge.generate();
        AuthenticationProof proof = AuthenticationProof.generate(
                challenge,
                identity,
                keyPair,
                authService.domain()
        );

        // First verification
        AuthenticationResult first = authService.verifyDirect(challenge, proof);
        assertEquals(AuthenticationResult.SUCCESS, first);

        // Second verification using same challenge instance
        AuthenticationResult second = authService.verifyDirect(challenge, proof);
        assertEquals(AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED, second);
    }
}