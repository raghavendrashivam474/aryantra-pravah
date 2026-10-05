package com.aryntra.pravah.security.trust;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.authentication.AuthWireCodec;
import com.aryntra.pravah.security.authentication.AuthenticationChallenge;
import com.aryntra.pravah.security.authentication.AuthenticationProof;
import com.aryntra.pravah.security.authentication.AuthenticationResult;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityGenerator;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.4 PeerTrustManager & AuthWireCodec Unit Tests")
class PeerTrustManagerTest {

    private PeerTrustManager trustManager;
    private PeerId peerId;
    private IdentityKeyPair keyPair;
    private CryptographicIdentity identity;

    @BeforeEach
    void setUp() {
        trustManager = new PeerTrustManager();
        peerId = PeerId.of("peer-alice-01");
        keyPair = IdentityGenerator.generate();
        identity = CryptographicIdentity.fromKeyPair(peerId, keyPair);
    }

    @Test
    @DisplayName("Challenge wire serialization and deserialization roundtrip")
    void testChallengeCodecRoundtrip() {
        AuthenticationChallenge original = AuthenticationChallenge.generate();
        byte[] encoded = AuthWireCodec.encodeChallenge(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        AuthenticationChallenge decoded = AuthWireCodec.decodeChallenge(encoded);
        assertEquals(original.challengeId(), decoded.challengeId());
        assertArrayEquals(original.challengeBytes(), decoded.challengeBytes());
        assertEquals(original.expiryTime().toEpochMilli(), decoded.expiryTime().toEpochMilli());
    }

    @Test
    @DisplayName("Proof wire serialization and deserialization roundtrip")
    void testProofCodecRoundtrip() {
        AuthenticationChallenge chal = AuthenticationChallenge.generate();
        AuthenticationProof original = AuthenticationProof.generate(chal, identity, keyPair, "com.aryntra.pravah.auth.v1");

        byte[] encoded = AuthWireCodec.encodeProof(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        AuthenticationProof decoded = AuthWireCodec.decodeProof(encoded);
        assertEquals(original.claimantIdentity().peerId(), decoded.claimantIdentity().peerId());
        assertEquals(original.challengeId(), decoded.challengeId());
        assertArrayEquals(original.signatureBytes(), decoded.signatureBytes());
    }

    @Test
    @DisplayName("Happy path: UNKNOWN -> AUTHENTICATING -> AUTHENTICATED -> TRUSTED")
    void testHappyPathAuthentication() {
        assertEquals(TrustState.UNKNOWN, trustManager.getTrustState(peerId));
        assertFalse(trustManager.isPeerTrusted(peerId));

        AtomicReference<TrustState> capturedNewState = new AtomicReference<>();
        trustManager.addTrustStateListener((id, oldS, newS) -> capturedNewState.set(newS));

        // 1. Issue challenge
        AuthenticationChallenge challenge = trustManager.issueChallengeForPeer(peerId);
        assertEquals(TrustState.AUTHENTICATING, trustManager.getTrustState(peerId));
        assertEquals(TrustState.AUTHENTICATING, capturedNewState.get());

        // 2. Generate valid proof
        AuthenticationProof proof = AuthenticationProof.generate(challenge, identity, keyPair, trustManager.authService().domain());

        // 3. Evaluate proof
        AuthenticationResult result = trustManager.evaluateProof(peerId, proof);
        assertEquals(AuthenticationResult.SUCCESS, result);
        assertEquals(TrustState.TRUSTED, trustManager.getTrustState(peerId));
        assertTrue(trustManager.isPeerTrusted(peerId));
    }

    @Test
    @DisplayName("Invalid proof causes transition to REJECTED")
    void testInvalidProofTransitionsToRejected() {
        AuthenticationChallenge challenge = trustManager.issueChallengeForPeer(peerId);

        // Generate proof for wrong challenge ID
        AuthenticationProof badProof = new AuthenticationProof(identity, "wrong-chal-id", new byte[64]);

        AuthenticationResult result = trustManager.evaluateProof(peerId, badProof);
        assertFalse(result.isSuccess());
        assertEquals(TrustState.REJECTED, trustManager.getTrustState(peerId));
        assertFalse(trustManager.isPeerTrusted(peerId));
    }

    @Test
    @DisplayName("PeerId mismatch causes immediate REJECTED state")
    void testPeerIdMismatch() {
        trustManager.issueChallengeForPeer(peerId);

        PeerId otherPeerId = PeerId.of("peer-eve-attacker");
        IdentityKeyPair otherKeyPair = IdentityGenerator.generate();
        CryptographicIdentity otherIdentity = CryptographicIdentity.fromKeyPair(otherPeerId, otherKeyPair);
        AuthenticationChallenge fakeChallenge = AuthenticationChallenge.generate();
        AuthenticationProof proof = AuthenticationProof.generate(fakeChallenge, otherIdentity, otherKeyPair, trustManager.authService().domain());

        // Present Eve's proof under Alice's identity
        AuthenticationResult result = trustManager.evaluateProof(peerId, proof);
        assertEquals(AuthenticationResult.MALFORMED_INPUT, result);
        assertEquals(TrustState.REJECTED, trustManager.getTrustState(peerId));
    }
}
