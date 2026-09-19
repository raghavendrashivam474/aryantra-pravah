package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S6.2 - Multi-Path Peer Representation Tests")
class MultiPathRepresentationTest {

    private PeerConnectivityRegistry registry;
    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        registry = new PeerConnectivityRegistry();
    }

    @Test
    @DisplayName("A single PeerId can have multiple concurrent paths of different transports/endpoints")
    void testSinglePeerMultiplePaths() {
        PathId path1 = PathId.of("alice-tcp-lan");
        PathId path2 = PathId.of("alice-tcp-direct");
        PathId path3 = PathId.of("alice-ble-candidate");

        ConnectivityPath p1 = ConnectivityPath.active(
                path1, alice, "TCP", EndpointAddress.tcp("192.168.1.10", 52132), "conn-tcp-10");
        ConnectivityPath p2 = ConnectivityPath.active(
                path2, alice, "TCP", EndpointAddress.tcp("10.0.0.5", 52132), "conn-tcp-11");
        ConnectivityPath p3 = ConnectivityPath.candidate(
                path3, alice, "BLE", EndpointAddress.of("ble", "AA:BB:CC:DD:EE:01", 0));

        registry.registerPath(alice, p1);
        registry.registerPath(alice, p2);
        registry.registerPath(alice, p3);

        Optional<PeerConnectivity> maybeConn = registry.lookup(alice);
        assertTrue(maybeConn.isPresent());

        PeerConnectivity conn = maybeConn.get();
        assertEquals(3, conn.pathCount());
        assertEquals(alice, conn.peerId());

        // Verify active vs candidate paths
        List<ConnectivityPath> active = conn.activePaths();
        assertEquals(2, active.size());
        assertTrue(active.contains(p1));
        assertTrue(active.contains(p2));

        List<ConnectivityPath> candidates = conn.candidatePaths();
        assertEquals(1, candidates.size());
        assertTrue(candidates.contains(p3));
    }

    @Test
    @DisplayName("Removing one path does not delete or affect other paths or the peer identity")
    void testRemoveOnePathPreservesOthers() {
        PathId path1 = PathId.of("path-lan");
        PathId path2 = PathId.of("path-wan");

        ConnectivityPath p1 = ConnectivityPath.active(
                path1, alice, "TCP", EndpointAddress.tcp("192.168.1.5", 5000), "conn-1");
        ConnectivityPath p2 = ConnectivityPath.active(
                path2, alice, "TCP", EndpointAddress.tcp("203.0.113.1", 5000), "conn-2");

        registry.registerPath(alice, p1);
        registry.registerPath(alice, p2);

        PeerConnectivity conn = registry.lookup(alice).orElseThrow();
        assertEquals(2, conn.pathCount());

        // Remove path 1
        Optional<ConnectivityPath> removed = conn.removePath(path1);
        assertTrue(removed.isPresent());
        assertEquals(p1, removed.get());

        // Alice still has path 2 and remains registered
        assertEquals(1, conn.pathCount());
        assertTrue(conn.findPath(path2).isPresent());
        assertTrue(conn.hasActivePath());
        assertEquals(alice, conn.peerId());
    }

    @Test
    @DisplayName("Deactivating one path leaves other active paths usable")
    void testDeactivateOnePathLeavesOthersActive() {
        PathId path1 = PathId.of("path-primary");
        PathId path2 = PathId.of("path-backup");

        ConnectivityPath p1 = ConnectivityPath.active(
                path1, alice, "TCP", EndpointAddress.tcp("192.168.1.50", 5001), "conn-primary");
        ConnectivityPath p2 = ConnectivityPath.active(
                path2, alice, "TCP", EndpointAddress.tcp("192.168.1.51", 5002), "conn-backup");

        registry.registerPath(alice, p1);
        registry.registerPath(alice, p2);

        PeerConnectivity conn = registry.lookup(alice).orElseThrow();
        assertEquals(2, conn.activePaths().size());

        // Deactivate path 1
        conn.addPath(p1.deactivate());

        assertEquals(1, conn.activePaths().size());
        assertEquals(p2, conn.activePaths().get(0));
        assertTrue(conn.hasActivePath());
    }

    @Test
    @DisplayName("Lookup by connectionId identifies the correct peer across multiple paths")
    void testLookupByConnectionId() {
        PathId pAlice1 = PathId.of("alice-1");
        PathId pAlice2 = PathId.of("alice-2");
        PathId pBob1 = PathId.of("bob-1");

        registry.registerPath(alice, ConnectivityPath.active(
                pAlice1, alice, "TCP", EndpointAddress.tcp("192.168.1.1", 9001), "conn-a1"));
        registry.registerPath(alice, ConnectivityPath.active(
                pAlice2, alice, "TCP", EndpointAddress.tcp("192.168.1.2", 9002), "conn-a2"));
        registry.registerPath(bob, ConnectivityPath.active(
                pBob1, bob, "TCP", EndpointAddress.tcp("192.168.1.3", 9003), "conn-b1"));

        Optional<PeerConnectivity> foundAlice1 = registry.lookupByConnectionId("conn-a1");
        assertTrue(foundAlice1.isPresent());
        assertEquals(alice, foundAlice1.get().peerId());

        Optional<PeerConnectivity> foundAlice2 = registry.lookupByConnectionId("conn-a2");
        assertTrue(foundAlice2.isPresent());
        assertEquals(alice, foundAlice2.get().peerId());

        Optional<PeerConnectivity> foundBob = registry.lookupByConnectionId("conn-b1");
        assertTrue(foundBob.isPresent());
        assertEquals(bob, foundBob.get().peerId());

        Optional<PeerConnectivity> notFound = registry.lookupByConnectionId("unknown-conn");
        assertTrue(notFound.isEmpty());
    }

    @Test
    @DisplayName("Peer removal removes entire connectivity record without side effects on others")
    void testRemovePeer() {
        registry.registerPath(alice, ConnectivityPath.candidate(
                PathId.of("p1"), alice, "TCP", EndpointAddress.tcp("127.0.0.1", 8080)));
        registry.registerPath(bob, ConnectivityPath.candidate(
                PathId.of("p2"), bob, "TCP", EndpointAddress.tcp("127.0.0.1", 8081)));

        assertEquals(2, registry.size());

        Optional<PeerConnectivity> removed = registry.removePeer(alice);
        assertTrue(removed.isPresent());
        assertEquals(alice, removed.get().peerId());
        assertEquals(1, registry.size());

        assertFalse(registry.contains(alice));
        assertTrue(registry.contains(bob));
    }
}
