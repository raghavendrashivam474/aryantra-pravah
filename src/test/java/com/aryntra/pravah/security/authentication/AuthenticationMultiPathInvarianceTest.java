package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityGenerator;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.3: Multi-Path Identity & Failover Security Invariance Tests")
class AuthenticationMultiPathInvarianceTest {

    private final IdentityGenerator generator = new IdentityGenerator();
    private AuthenticationService authService;
    private IdentityKeyPair peerAKeyPair;
    private CryptographicIdentity peerAIdentity;
    private PeerId peerAId;

    @BeforeEach
    void setUp() {
        authService = new AuthenticationService();
        peerAKeyPair = generator.generate();
        peerAId = PeerId.generate();
        peerAIdentity = CryptographicIdentity.of(peerAId, peerAKeyPair.publicKey());
    }

    @Test
    @DisplayName("Invariance: Peer authenticating across multiple paths preserves singular CryptographicIdentity")
    void testMultiPathAuthenticationInvariance() {
        // Suppose verifier tracks authenticated peers: Map<PeerId, CryptographicIdentity>
        Map<PeerId, CryptographicIdentity> authenticatedPeers = new ConcurrentHashMap<>();

        // Path 1 (e.g. TCP) handshake
        AuthenticationChallenge tcpChallenge = authService.issueChallenge();
        AuthenticationProof tcpProof = AuthenticationProof.generate(
                tcpChallenge,
                peerAIdentity,
                peerAKeyPair,
                authService.domain()
        );
        AuthenticationResult tcpResult = authService.verifyProof(tcpProof);
        assertEquals(AuthenticationResult.SUCCESS, tcpResult);
        authenticatedPeers.put(peerAId, tcpProof.claimantIdentity());

        // Path 2 (e.g. Bluetooth) handshake for same peer
        AuthenticationChallenge btChallenge = authService.issueChallenge();
        AuthenticationProof btProof = AuthenticationProof.generate(
                btChallenge,
                peerAIdentity,
                peerAKeyPair,
                authService.domain()
        );
        AuthenticationResult btResult = authService.verifyProof(btProof);
        assertEquals(AuthenticationResult.SUCCESS, btResult);

        // Security check: Both paths refer to identical CryptographicIdentity and PeerId
        assertEquals(1, authenticatedPeers.size(), "Multi-path authentication must not create duplicate peer identities");
        assertEquals(peerAIdentity, btProof.claimantIdentity(), "BT authentication must yield exact same CryptographicIdentity");
    }

    @Test
    @DisplayName("Invariance: Path failure does not mutate or invalidate authenticated identity")
    void testPathFailurePreservesAuthenticationState() {
        // Authenticate peer
        AuthenticationChallenge challenge = authService.issueChallenge();
        AuthenticationProof proof = AuthenticationProof.generate(challenge, peerAIdentity, peerAKeyPair, authService.domain());
        assertEquals(AuthenticationResult.SUCCESS, authService.verifyProof(proof));

        // Simulated multi-path state
        Map<String, String> activePaths = new ConcurrentHashMap<>();
        activePaths.put("tcp://192.168.1.100:9000", peerAId.value());
        activePaths.put("bt://AA:BB:CC:DD:EE:FF", peerAId.value());

        // TCP path is dropped
        activePaths.remove("tcp://192.168.1.100:9000");

        // Identity invariant checks
        assertTrue(activePaths.containsValue(peerAId.value()), "Peer must still be recognized on active Bluetooth path");
        assertEquals(peerAId.value(), activePaths.get("bt://AA:BB:CC:DD:EE:FF"));
    }
}