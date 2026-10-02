package com.aryntra.pravah.peer.discovery;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathState;
import com.aryntra.pravah.connectivity.PeerConnectivity;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.discovery.bluetooth.BluetoothPeerDiscovery;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.presence.PeerPresenceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S8.3 - Hybrid Discovery & Addressing Unit & Contract Tests")
class HybridDiscoveryTest {

    private PeerRegistry peerRegistry;
    private PeerPresenceManager presenceManager;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerPresenceBridge bridge;

    private static final PeerId PEER_A = PeerId.of("peer-node-alpha");
    private static final String TCP_HOST = "192.168.1.50";
    private static final int TCP_PORT = 8080;
    private static final String BT_MAC = "AA:BB:CC:DD:EE:99";
    private static final int BT_CHANNEL = 4;

    @BeforeEach
    void setUp() {
        peerRegistry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(30_000L);
        connectivityRegistry = new PeerConnectivityRegistry();
        bridge = new PeerPresenceBridge(peerRegistry, presenceManager, connectivityRegistry);
        presenceManager.start();
    }

    @AfterEach
    void tearDown() {
        if (presenceManager != null) {
            presenceManager.stop();
        }
    }

    @Test
    @DisplayName("Single Candidate: Discovered peer creates deterministic CANDIDATE path")
    void testSingleCandidateDiscovery() {
        DiscoveredAddressCandidate tcpCandidate = DiscoveredAddressCandidate.tcp(PEER_A, TCP_HOST, TCP_PORT);
        bridge.onCandidateDiscovered(tcpCandidate);

        assertTrue(peerRegistry.contains(PEER_A));
        assertEquals(PeerPresenceState.AVAILABLE, presenceManager.getPresence(PEER_A).state());

        Optional<PeerConnectivity> connOpt = connectivityRegistry.lookup(PEER_A);
        assertTrue(connOpt.isPresent());
        PeerConnectivity conn = connOpt.get();

        assertEquals(1, conn.pathCount());
        ConnectivityPath path = conn.allPaths().iterator().next();
        assertEquals(PathState.CANDIDATE, path.state());
        assertEquals("tcp", path.transportName());
        assertEquals(tcpCandidate.toPathId(), path.pathId());
    }

    @Test
    @DisplayName("Idempotency: Duplicate announcements of same endpoint produce single path")
    void testDuplicateCandidateIdempotency() {
        DiscoveredAddressCandidate candidate1 = DiscoveredAddressCandidate.bluetooth(PEER_A, BT_MAC, BT_CHANNEL);
        DiscoveredAddressCandidate candidate2 = DiscoveredAddressCandidate.bluetooth(PEER_A, BT_MAC, BT_CHANNEL);

        bridge.onCandidateDiscovered(candidate1);
        bridge.onCandidateDiscovered(candidate2);

        PeerConnectivity conn = connectivityRegistry.getOrCreate(PEER_A);
        assertEquals(1, conn.pathCount(), "Duplicate discoveries should not generate multiple duplicate paths");
    }

    @Test
    @DisplayName("Multi-Path Identity: Single PeerId acquires both TCP and Bluetooth candidates")
    void testMultiPathCandidateRegistration() {
        DiscoveredAddressCandidate tcpCandidate = DiscoveredAddressCandidate.tcp(PEER_A, TCP_HOST, TCP_PORT);
        DiscoveredAddressCandidate btCandidate = DiscoveredAddressCandidate.bluetooth(PEER_A, BT_MAC, BT_CHANNEL);

        // Discovery via two distinct mechanisms
        bridge.onCandidateDiscovered(tcpCandidate);
        bridge.onCandidateDiscovered(btCandidate);

        // Verify Peer Identity invariant: EXACTLY ONE peer record
        assertEquals(1, peerRegistry.size(), "There must only be ONE registered logical peer");
        assertEquals(1, connectivityRegistry.size(), "There must only be ONE PeerConnectivity entry");

        PeerConnectivity conn = connectivityRegistry.getOrCreate(PEER_A);
        assertEquals(2, conn.pathCount(), "Peer must own 2 candidate connectivity paths");

        List<ConnectivityPath> candidatePaths = conn.candidatePaths();
        assertEquals(2, candidatePaths.size());

        boolean hasTcp = candidatePaths.stream().anyMatch(p -> p.transportName().equals("tcp"));
        boolean hasBt = candidatePaths.stream().anyMatch(p -> p.transportName().equals("bluetooth"));

        assertTrue(hasTcp, "Should contain TCP candidate path");
        assertTrue(hasBt, "Should contain Bluetooth candidate path");
    }

    @Test
    @DisplayName("Lifecycle: Candidate -> Active -> Selective Disconnect")
    void testPathLifecycleTransitions() {
        DiscoveredAddressCandidate tcpCandidate = DiscoveredAddressCandidate.tcp(PEER_A, TCP_HOST, TCP_PORT);
        DiscoveredAddressCandidate btCandidate = DiscoveredAddressCandidate.bluetooth(PEER_A, BT_MAC, BT_CHANNEL);

        bridge.onCandidateDiscovered(tcpCandidate);
        bridge.onCandidateDiscovered(btCandidate);

        // Activate Bluetooth connection
        bridge.handlePeerConnected(PEER_A, "bt:" + BT_MAC);

        PeerConnectivity conn = connectivityRegistry.getOrCreate(PEER_A);
        assertEquals(1, conn.activePaths().size());
        assertEquals(1, conn.candidatePaths().size());

        ConnectivityPath activePath = conn.activePaths().get(0);
        assertEquals("bluetooth", activePath.transportName());
        assertEquals("bt:" + BT_MAC, activePath.connectionId());

        // Now activate TCP connection as well (Multi-active)
        bridge.handlePeerConnected(PEER_A, "tcp:192.168.1.50:8080");
        assertEquals(2, conn.activePaths().size());
        assertEquals(0, conn.candidatePaths().size());

        // Selectively drop only the Bluetooth path
        bridge.handleConnectionClosed(PEER_A, "bt:" + BT_MAC);

        assertEquals(1, conn.activePaths().size(), "TCP path must remain ACTIVE");
        assertEquals("tcp", conn.activePaths().get(0).transportName());
        assertTrue(conn.hasActivePath());
        assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(PEER_A).state());

        // Drop the TCP path as well
        bridge.handleConnectionClosed(PEER_A, "tcp:192.168.1.50:8080");
        assertEquals(0, conn.activePaths().size());
        assertFalse(conn.hasActivePath());
        // Disconnected but still within TTL -> AVAILABLE
        assertEquals(PeerPresenceState.AVAILABLE, presenceManager.getPresence(PEER_A).state());
    }

    @Test
    @DisplayName("BluetoothPeerDiscovery: Simulated airspace discovery triggers candidates")
    void testBluetoothPeerDiscoveryAirspace() throws Exception {
        PeerId node1 = PeerId.of("node-1");
        PeerId node2 = PeerId.of("node-2");

        BluetoothPeerDiscovery disc1 = new BluetoothPeerDiscovery(node1, "11:22:33:44:55:66", 1);
        BluetoothPeerDiscovery disc2 = new BluetoothPeerDiscovery(node2, "AA:BB:CC:DD:EE:FF", 2);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DiscoveredAddressCandidate> found = new AtomicReference<>();

        disc1.addCandidateListener(c -> {
            found.set(c);
            latch.countDown();
        });

        disc1.start();
        disc2.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Node 1 should discover Node 2 over Bluetooth");
        assertNotNull(found.get());
        assertEquals(node2, found.get().peerId());
        assertEquals("bluetooth", found.get().transportScheme());
        assertEquals("AA:BB:CC:DD:EE:FF", found.get().endpointAddress().host());
        assertEquals(2, found.get().endpointAddress().port());

        disc1.stop();
        disc2.stop();
    }
}