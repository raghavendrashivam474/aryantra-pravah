package com.aryntra.pravah.security.trust;

import com.aryntra.pravah.connectivity.*;
import com.aryntra.pravah.peer.*;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.protocol.*;
import com.aryntra.pravah.security.authentication.AuthWireCodec;
import com.aryntra.pravah.security.authentication.AuthenticationChallenge;
import com.aryntra.pravah.security.authentication.AuthenticationProof;
import com.aryntra.pravah.security.authentication.AuthenticationResult;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityGenerator;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.4 Trust Lifecycle & Multi-Path Invariance Tests")
class TrustLifecycleIntegrationTest {

    private MockTransport transport;
    private PeerRegistry peerRegistry;
    private PeerPresenceManager presenceManager;
    private PeerPresenceBridge presenceBridge;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerTrustManager trustManager;
    private PeerRouter peerRouter;
    private PeerId localPeerId;
    private IdentityKeyPair localKeyPair;
    private CryptographicIdentity localIdentity;
    private PeerId remotePeerId;
    private IdentityKeyPair remoteKeyPair;
    private CryptographicIdentity remoteIdentity;

    @BeforeEach
    void setUp() {
        IdentityGenerator gen = new IdentityGenerator();
        transport = new MockTransport();
        peerRegistry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(30_000L);
        presenceBridge = new PeerPresenceBridge(peerRegistry, presenceManager);
        connectivityRegistry = new PeerConnectivityRegistry();
        trustManager = new PeerTrustManager();

        localPeerId = PeerId.of("node-alice");
        localKeyPair = gen.generate();
        localIdentity = CryptographicIdentity.fromKeyPair(localPeerId, localKeyPair);

        remotePeerId = PeerId.of("node-bob");
        remoteKeyPair = gen.generate();
        remoteIdentity = CryptographicIdentity.fromKeyPair(remotePeerId, remoteKeyPair);

        peerRouter = new PeerRouter(
                peerRegistry,
                transport,
                connectivityRegistry,
                PathSelectionPolicy.defaultPolicy(),
                trustManager
        );
    }

    @Test
    @DisplayName("Router fails closed when peer is UNKNOWN or AUTHENTICATING")
    void testRouterFailsClosedForUntrustedPeer() {
        String connId = "tcp-conn-1";
        peerRegistry.register(remotePeerId, connId);
        connectivityRegistry.registerPath(remotePeerId, new ConnectivityPath(
                PathId.of("path-tcp"),
                remotePeerId,
                "tcp",
                EndpointAddress.tcp("127.0.0.1", 9001),
                PathState.ACTIVE,
                connId
        ));

        // State is UNKNOWN initially
        assertEquals(TrustState.UNKNOWN, trustManager.getTrustState(remotePeerId));
        Message appMsg = new Message(MessageType.MESSAGE, localPeerId.value(), "msg-1", "Hello Bob".getBytes());

        // Send should fail
        PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> {
            peerRouter.send(remotePeerId, appMsg);
        });
        assertTrue(ex.getMessage().contains("is not TRUSTED"));

        // Transition to AUTHENTICATING
        trustManager.issueChallengeForPeer(remotePeerId);
        assertEquals(TrustState.AUTHENTICATING, trustManager.getTrustState(remotePeerId));

        assertThrows(PeerRoutingException.class, () -> {
            peerRouter.send(remotePeerId, appMsg);
        });
    }

    @Test
    @DisplayName("Complete end-to-end handshake promotes peer to TRUSTED and enables routing")
    void testEndToEndHandshakeEnablesRouting() {
        PeerConnectionCoordinator coordinator = new PeerConnectionCoordinator(
                transport,
                peerRegistry,
                presenceBridge,
                new ProtocolSessionManager(),
                trustManager,
                localKeyPair,
                localIdentity
        );

        String connId = "conn-bob-tcp";
        coordinator.onConnectionOpened(connId);

        // 1. Bob sends JOIN
        Message joinMsg = new Message(MessageType.JOIN, remotePeerId.value(), "join-1", new byte[0]);
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(joinMsg)));
        assertTrue(peerRegistry.contains(remotePeerId));
        assertEquals(TrustState.UNKNOWN, trustManager.getTrustState(remotePeerId));

        // 2. Alice issues challenge
        boolean initiated = coordinator.initiateAuthentication(remotePeerId, connId);
        assertTrue(initiated);
        assertEquals(TrustState.AUTHENTICATING, trustManager.getTrustState(remotePeerId));

        // Capture outgoing challenge frame from transport
        byte[] challengeFrame = transport.lastSentPayload.get(connId);
        assertNotNull(challengeFrame);
        Message parsedChalMsg = MessageParser.parse(new FrameDecoder().feed(challengeFrame).get(0));
        assertEquals(MessageType.AUTH_CHALLENGE, parsedChalMsg.type());
        AuthenticationChallenge receivedChallenge = AuthWireCodec.decodeChallenge(parsedChalMsg.payload());

        // 3. Bob constructs proof and responds
        AuthenticationProof bobProof = AuthenticationProof.generate(
                receivedChallenge,
                remoteIdentity,
                remoteKeyPair,
                trustManager.authService().domain()
        );
        byte[] proofPayload = AuthWireCodec.encodeProof(bobProof);
        Message proofMsg = new Message(MessageType.AUTH_PROOF, remotePeerId.value(), "proof-1", proofPayload);

        // Deliver Bob's proof to Alice's coordinator
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(proofMsg)));

        // 4. Invariant check: Peer is now TRUSTED
        assertEquals(TrustState.TRUSTED, trustManager.getTrustState(remotePeerId));
        assertTrue(trustManager.isPeerTrusted(remotePeerId));

        // 5. Router successfully sends application message
        Message appMsg = new Message(MessageType.MESSAGE, localPeerId.value(), "app-1", "Verified secret".getBytes());
        boolean sent = peerRouter.send(remotePeerId, appMsg);
        assertTrue(sent);
    }

    @Test
    @DisplayName("Multi-Path Invariance: Trust is peer-level and persists across transports")
    void testMultiPathTrustInvariance() {
        // Authenticate peer
        AuthenticationChallenge chal = trustManager.issueChallengeForPeer(remotePeerId);
        AuthenticationProof proof = AuthenticationProof.generate(chal, remoteIdentity, remoteKeyPair, trustManager.authService().domain());
        trustManager.evaluateProof(remotePeerId, proof);
        assertEquals(TrustState.TRUSTED, trustManager.getTrustState(remotePeerId));

        // Setup TCP path
        ConnectivityPath tcpPath = new ConnectivityPath(
                PathId.of("path-tcp"),
                remotePeerId,
                "tcp",
                EndpointAddress.tcp("192.168.1.10", 9001),
                PathState.ACTIVE,
                "conn-tcp"
        );
        connectivityRegistry.registerPath(remotePeerId, tcpPath);

        // Route over TCP
        Message msg1 = new Message(MessageType.MESSAGE, localPeerId.value(), "m1", "via TCP".getBytes());
        assertTrue(peerRouter.send(remotePeerId, msg1));
        assertNotNull(transport.lastSentPayload.get("conn-tcp"));

        // Attach secondary Bluetooth path
        ConnectivityPath btPath = new ConnectivityPath(
                PathId.of("path-bt"),
                remotePeerId,
                "bluetooth",
                EndpointAddress.of("bluetooth", "00:11:22:33:44:55", 0),
                PathState.ACTIVE,
                "conn-bt"
        );
        connectivityRegistry.registerPath(remotePeerId, btPath);

        // Security Invariant: Peer remains TRUSTED without re-authenticating over Bluetooth
        assertEquals(TrustState.TRUSTED, trustManager.getTrustState(remotePeerId));

        // Drop TCP path
        connectivityRegistry.registerPath(remotePeerId, tcpPath.deactivate());

        // Security Invariant: Path failure != Trust failure
        assertEquals(TrustState.TRUSTED, trustManager.getTrustState(remotePeerId));

        // Route over Bluetooth
        Message msg2 = new Message(MessageType.MESSAGE, localPeerId.value(), "m2", "via BT failover".getBytes());
        assertTrue(peerRouter.send(remotePeerId, msg2));
        assertNotNull(transport.lastSentPayload.get("conn-bt"));
    }

    @Test
    @DisplayName("Replay Attack: Submitting proof with already-consumed challenge is REJECTED")
    void testReplayAttackRejected() {
        AuthenticationChallenge challenge = trustManager.issueChallengeForPeer(remotePeerId);
        AuthenticationProof proof = AuthenticationProof.generate(challenge, remoteIdentity, remoteKeyPair, trustManager.authService().domain());

        // First attempt succeeds
        AuthenticationResult res1 = trustManager.evaluateProof(remotePeerId, proof);
        assertEquals(AuthenticationResult.SUCCESS, res1);
        assertEquals(TrustState.TRUSTED, trustManager.getTrustState(remotePeerId));

        // Attacker replays exact same proof
        AuthenticationResult res2 = trustManager.evaluateProof(remotePeerId, proof);
        assertEquals(AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED, res2);
        assertEquals(TrustState.REJECTED, trustManager.getTrustState(remotePeerId));
        assertFalse(trustManager.isPeerTrusted(remotePeerId));
    }

    @Test
    @DisplayName("Policy Rejection: Failed custom trust policy transitions to REJECTED")
    void testPolicyRejection() {
        // Custom policy rejecting all peers
        PeerTrustManager rejectingManager = new PeerTrustManager(
                new com.aryntra.pravah.security.authentication.AuthenticationService(),
                (identity, result) -> false
        );
        AuthenticationChallenge chal = rejectingManager.issueChallengeForPeer(remotePeerId);
        AuthenticationProof proof = AuthenticationProof.generate(chal, remoteIdentity, remoteKeyPair, rejectingManager.authService().domain());
        AuthenticationResult result = rejectingManager.evaluateProof(remotePeerId, proof);
        assertEquals(AuthenticationResult.SUCCESS, result);
        assertEquals(TrustState.REJECTED, rejectingManager.getTrustState(remotePeerId));
        assertFalse(rejectingManager.isPeerTrusted(remotePeerId));
    }

    // --- Lightweight Mock Transport for deterministic verification ---
    private static class MockTransport implements Transport {
        final Map<String, byte[]> lastSentPayload = new ConcurrentHashMap<>();

        @Override
        public String getName() {
            return "mock";
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void send(String connectionId, byte[] payload) {
            lastSentPayload.put(connectionId, payload);
        }

        @Override
        public void setListener(TransportListener listener) {}

        @Override
        public TransportCapabilities getCapabilities() {
            return TransportCapabilities.tcp();
        }
    }
}