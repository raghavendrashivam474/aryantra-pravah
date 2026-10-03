package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityGenerator;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.3: AuthenticationService Proof Verification Tests")
class AuthenticationServiceTest {

    private final IdentityGenerator generator = new IdentityGenerator();
    private AuthenticationService authService;
    private IdentityKeyPair claimantKeyPair;
    private CryptographicIdentity claimantIdentity;
    private PeerId claimantPeerId;

    @BeforeEach
    void setUp() {
        authService = new AuthenticationService();
        claimantKeyPair = generator.generate();
        claimantPeerId = PeerId.generate();
        claimantIdentity = CryptographicIdentity.of(claimantPeerId, claimantKeyPair.publicKey());
    }

    @Test
    @DisplayName("Happy path: Valid proof of possession returns SUCCESS")
    void testHappyPathAuthentication() {
        // 1. Verifier issues fresh challenge
        AuthenticationChallenge challenge = authService.issueChallenge();
        assertEquals(1, authService.pendingChallengeCount());

        // 2. Claimant generates cryptographic proof using its private key
        AuthenticationProof proof = AuthenticationProof.generate(
                challenge,
                claimantIdentity,
                claimantKeyPair,
                authService.domain()
        );

        // 3. Verifier checks proof
        AuthenticationResult result = authService.verifyProof(proof);

        assertEquals(AuthenticationResult.SUCCESS, result);
        assertTrue(result.isSuccess());
        assertEquals(0, authService.pendingChallengeCount(), "Challenge should be consumed and removed from pending");
    }

    @Test
    @DisplayName("Wrong private key: Proof generated with incorrect key returns INVALID_PROOF")
    void testWrongPrivateKeyFails() {
        AuthenticationChallenge challenge = authService.issueChallenge();

        // Attacker creates their own keypair
        IdentityKeyPair attackerKeyPair = generator.generate();

        // Attacker claims legitimate identity but signs with their own private key
        AuthenticationProof forgedProof = AuthenticationProof.generate(
                challenge,
                claimantIdentity, // Claims Peer A
                attackerKeyPair,  // Signs with Attacker Key
                authService.domain()
        );

        AuthenticationResult result = authService.verifyProof(forgedProof);

        assertEquals(AuthenticationResult.INVALID_PROOF, result);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Tampered Identity: Modifying the claimed PeerId fails verification")
    void testTamperedPeerIdFails() {
        AuthenticationChallenge challenge = authService.issueChallenge();

        // Legitimate proof for claimantIdentity
        AuthenticationProof proof = AuthenticationProof.generate(
                challenge,
                claimantIdentity,
                claimantKeyPair,
                authService.domain()
        );

        // Tamper by swapping the identity inside the proof to a different PeerId
        PeerId differentPeerId = PeerId.generate();
        CryptographicIdentity tamperedIdentity = CryptographicIdentity.of(differentPeerId, claimantKeyPair.publicKey());
        AuthenticationProof tamperedProof = new AuthenticationProof(
                tamperedIdentity,
                proof.challengeId(),
                proof.signatureBytes()
        );

        AuthenticationResult result = authService.verifyProof(tamperedProof);

        assertEquals(AuthenticationResult.INVALID_PROOF, result);
    }

    @Test
    @DisplayName("Cross-Domain attack: Proof generated for different domain returns INVALID_PROOF")
    void testCrossDomainProofFails() {
        AuthenticationChallenge challenge = authService.issueChallenge();

        // Proof created under a foreign domain
        AuthenticationProof foreignDomainProof = AuthenticationProof.generate(
                challenge,
                claimantIdentity,
                claimantKeyPair,
                "com.otherapp.security.domain.v9"
        );

        AuthenticationResult result = authService.verifyProof(foreignDomainProof);

        assertEquals(AuthenticationResult.INVALID_PROOF, result);
    }

    @Test
    @DisplayName("Unknown or unissued challenge ID returns CHALLENGE_EXPIRED_OR_CONSUMED")
    void testUnknownChallengeId() {
        AuthenticationChallenge mockChallenge = AuthenticationChallenge.generate(); // Not registered in authService

        AuthenticationProof proof = AuthenticationProof.generate(
                mockChallenge,
                claimantIdentity,
                claimantKeyPair,
                authService.domain()
        );

        AuthenticationResult result = authService.verifyProof(proof);

        assertEquals(AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED, result);
    }

    @Test
    @DisplayName("Direct verification succeeds with fresh challenge and valid proof")
    void testDirectVerification() {
        AuthenticationChallenge challenge = AuthenticationChallenge.generate();

        AuthenticationProof proof = AuthenticationProof.generate(
                challenge,
                claimantIdentity,
                claimantKeyPair,
                authService.domain()
        );

        AuthenticationResult result = authService.verifyDirect(challenge, proof);

        assertEquals(AuthenticationResult.SUCCESS, result);
        assertTrue(challenge.isConsumed(), "Challenge must be consumed after verification");
    }
}