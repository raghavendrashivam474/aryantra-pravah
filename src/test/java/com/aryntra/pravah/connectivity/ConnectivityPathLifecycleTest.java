package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.discovery.DiscoveredPeer;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.presence.PeerPresenceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying S6.4 Connectivity Integration:
 * - Candidate path creation from discovery
 * - Duplicate discovery idempotence
 * - Connection activation and deactivation
 * - Presence compatibility
 * - Peer identity preservation
 */
class ConnectivityPathLifecycleTest {

    private PeerRegistry registry;
    private PeerPresenceManager presenceManager;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerPresenceBridge bridge;

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(5000);
        connectivityRegistry = new PeerConnectivityRegistry();
        bridge = new PeerPresenceBridge(registry, presenceManager, connectivityRegistry);
    }

    @Nested
    @DisplayName("Candidate Path Creation Tests")
    class CandidateCreationTests {

        @Test
        @DisplayName("Discovery creates a CANDIDATE path in PeerConnectivityRegistry")
        void discoveryCreatesCandidatePath() {
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            bridge.onPeerDiscovered(peer);

            assertTrue(connectivityRegistry.contains(bob));
            PeerConnectivity connectivity = connectivityRegistry.getOrCreate(bob);
            assertEquals(1, connectivity.pathCount());

            List<ConnectivityPath> candidates = connectivity.candidatePaths();
            assertEquals(1, candidates.size());

            ConnectivityPath candidate = candidates.get(0);
            assertEquals(PathState.CANDIDATE, candidate.state());
            assertEquals("tcp", candidate.transportName());
            assertEquals("192.168.1.10", candidate.endpointAddress().host());
            assertEquals(9000, candidate.endpointAddress().port());
            assertNull(candidate.connectionId());
        }

        @Test
        @DisplayName("Duplicate discoveries of the same endpoint do not create duplicate paths")
        void duplicateDiscoveryIsIdempotent() {
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            bridge.onPeerDiscovered(peer);
            bridge.onPeerDiscovered(peer);
            bridge.onPeerDiscovered(peer);

            PeerConnectivity connectivity = connectivityRegistry.getOrCreate(bob);
            assertEquals(1, connectivity.pathCount(), "Duplicate discoveries must not create multiple paths");
        }

        @Test
        @DisplayName("Discovery of multiple different endpoints for same peer creates multiple candidate paths")
        void multipleEndpointsCreateMultipleCandidates() {
            DiscoveredPeer peer1 = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            DiscoveredPeer peer2 = new DiscoveredPeer(bob, "10.0.0.5", 9001);

            bridge.onPeerDiscovered(peer1);
            bridge.onPeerDiscovered(peer2);

            PeerConnectivity connectivity = connectivityRegistry.getOrCreate(bob);
            assertEquals(2, connectivity.pathCount());
            assertEquals(2, connectivity.candidatePaths().size());
        }
    }

    @Nested
    @DisplayName("Activation and Deactivation Tests")
    class LifecycleTransitionTests {

        @Test
        @DisplayName("Connecting a peer promotes candidate path to ACTIVE with connectionId")
        void connectionPromotesCandidateToActive() {
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            bridge.onPeerDiscovered(peer);

            bridge.handlePeerConnected(bob, "conn-bob-1");

            PeerConnectivity connectivity = connectivityRegistry.getOrCreate(bob);
            assertTrue(connectivity.hasActivePath());
            assertEquals(0, connectivity.candidatePaths().size());

            List<ConnectivityPath> activePaths = connectivity.activePaths();
            assertEquals(1, activePaths.size());

            ConnectivityPath active = activePaths.get(0);
            assertEquals(PathState.ACTIVE, active.state());
            assertEquals("conn-bob-1", active.connectionId());
            assertEquals("192.168.1.10", active.endpointAddress().host());
        }

        @Test
        @DisplayName("Disconnecting a peer marks active path as INACTIVE")
        void disconnectionMarksActiveAsInactive() {
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            bridge.onPeerDiscovered(peer);
            bridge.handlePeerConnected(bob, "conn-bob-1");

            bridge.handlePeerDisconnected(bob);

            PeerConnectivity connectivity = connectivityRegistry.getOrCreate(bob);
            assertFalse(connectivity.hasActivePath());
            assertEquals(1, connectivity.pathCount());

            ConnectivityPath path = connectivity.allPaths().iterator().next();
            assertEquals(PathState.INACTIVE, path.state());
            assertNull(path.connectionId());
        }

        @Test
        @DisplayName("Peer identity and record remain preserved after disconnection")
        void peerIdentityPreservedAfterDisconnection() {
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            bridge.onPeerDiscovered(peer);
            bridge.handlePeerConnected(bob, "conn-bob-1");
            bridge.handlePeerDisconnected(bob);

            // Peer still exists in registry
            assertTrue(registry.contains(bob));
            assertFalse(registry.lookup(bob).get().isConnected());

            // Peer still exists in connectivity registry
            assertTrue(connectivityRegistry.contains(bob));
            assertEquals(1, connectivityRegistry.getOrCreate(bob).pathCount());
        }

        @Test
        @DisplayName("Connecting without prior discovery creates active synthetic path")
        void connectionWithoutDiscoveryCreatesActivePath() {
            bridge.handlePeerConnected(alice, "conn-alice-direct");

            PeerConnectivity connectivity = connectivityRegistry.getOrCreate(alice);
            assertTrue(connectivity.hasActivePath());
            assertEquals(1, connectivity.activePaths().size());
            assertEquals("conn-alice-direct", connectivity.activePaths().get(0).connectionId());
        }
    }

    @Nested
    @DisplayName("Presence Compatibility Tests")
    class PresenceCompatibilityTests {

        @Test
        @DisplayName("Presence state transitions correctly alongside connectivity tracking")
        void presenceStateTransitionsPreserved() {
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);
            bridge.onPeerDiscovered(peer);

            // Presence should be AVAILABLE
            assertEquals(PeerPresenceState.AVAILABLE, presenceManager.getPresence(bob).state());

            // Connect -> CONNECTED
            bridge.handlePeerConnected(bob, "conn-1");
            assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(bob).state());

            // Disconnect -> AVAILABLE (TTL has not expired)
            bridge.handlePeerDisconnected(bob);
            assertEquals(PeerPresenceState.AVAILABLE, presenceManager.getPresence(bob).state());
        }

        @Test
        @DisplayName("Backward compatibility: bridge functions without ConnectivityRegistry")
        void backwardCompatibilityWithoutConnectivityRegistry() {
            PeerPresenceBridge legacyBridge = new PeerPresenceBridge(registry, presenceManager);
            DiscoveredPeer peer = new DiscoveredPeer(bob, "192.168.1.10", 9000);

            assertDoesNotThrow(() -> legacyBridge.onPeerDiscovered(peer));
            assertEquals(PeerPresenceState.AVAILABLE, presenceManager.getPresence(bob).state());

            assertDoesNotThrow(() -> legacyBridge.handlePeerConnected(bob, "conn-1"));
            assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(bob).state());

            assertDoesNotThrow(() -> legacyBridge.handlePeerDisconnected(bob));
            assertEquals(PeerPresenceState.AVAILABLE, presenceManager.getPresence(bob).state());
        }
    }
}