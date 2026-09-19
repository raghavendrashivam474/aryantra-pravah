package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S6.1 - Connectivity Model Tests")
class ConnectivityModelTest {

    @Nested
    @DisplayName("EndpointAddress Tests")
    class EndpointAddressTests {

        @Test
        @DisplayName("Valid TCP endpoint creation")
        void testTcpEndpointCreation() {
            EndpointAddress ep = EndpointAddress.tcp("192.168.1.100", 52132);
            assertEquals("192.168.1.100", ep.host());
            assertEquals(52132, ep.port());
            assertEquals("tcp", ep.transportScheme());
            assertEquals("tcp://192.168.1.100:52132", ep.toUriString());
            assertEquals("tcp://192.168.1.100:52132", ep.toString());
        }

        @Test
        @DisplayName("Custom scheme endpoint creation")
        void testCustomSchemeEndpoint() {
            EndpointAddress ep = EndpointAddress.of("ble", "AA:BB:CC:DD:EE:FF", 0);
            assertEquals("AA:BB:CC:DD:EE:FF", ep.host());
            assertEquals(0, ep.port());
            assertEquals("ble", ep.transportScheme());
            assertEquals("ble://AA:BB:CC:DD:EE:FF", ep.toUriString());
        }

        @Test
        @DisplayName("Validation prevents null/blank host and invalid ports")
        void testValidation() {
            assertThrows(NullPointerException.class, () -> new EndpointAddress(null, 8080, "tcp"));
            assertThrows(IllegalArgumentException.class, () -> new EndpointAddress("  ", 8080, "tcp"));
            assertThrows(IllegalArgumentException.class, () -> new EndpointAddress("localhost", -1, "tcp"));
            assertThrows(IllegalArgumentException.class, () -> new EndpointAddress("localhost", 65536, "tcp"));
        }

        @Test
        @DisplayName("Default scheme fallback to tcp if null/blank")
        void testDefaultScheme() {
            EndpointAddress ep = new EndpointAddress("localhost", 8080, null);
            assertEquals("tcp", ep.transportScheme());

            EndpointAddress ep2 = new EndpointAddress("localhost", 8080, "  ");
            assertEquals("tcp", ep2.transportScheme());
        }
    }

    @Nested
    @DisplayName("PathId Tests")
    class PathIdTests {

        @Test
        @DisplayName("Factory and equality")
        void testPathIdEquality() {
            PathId id1 = PathId.of("path-1");
            PathId id2 = PathId.of("path-1");
            PathId id3 = PathId.of("path-2");

            assertEquals(id1, id2);
            assertEquals(id1.hashCode(), id2.hashCode());
            assertNotEquals(id1, id3);
            assertEquals("path-1", id1.value());
            assertEquals("PathId[path-1]", id1.toString());
        }

        @Test
        @DisplayName("Generate unique IDs")
        void testPathIdGenerate() {
            PathId id1 = PathId.generate();
            PathId id2 = PathId.generate();
            assertNotNull(id1.value());
            assertNotEquals(id1, id2);
        }

        @Test
        @DisplayName("Validation prevents null or blank PathId")
        void testPathIdValidation() {
            assertThrows(IllegalArgumentException.class, () -> PathId.of(null));
            assertThrows(IllegalArgumentException.class, () -> PathId.of("  "));
        }
    }

    @Nested
    @DisplayName("ConnectivityPath Tests")
    class ConnectivityPathTests {

        private final PeerId alice = PeerId.of("alice");
        private final PathId pathId = PathId.of("path-tcp-1");
        private final EndpointAddress ep = EndpointAddress.tcp("127.0.0.1", 52132);

        @Test
        @DisplayName("Candidate path has no connectionId and is not active")
        void testCandidatePath() {
            ConnectivityPath path = ConnectivityPath.candidate(pathId, alice, "TCP", ep);

            assertEquals(pathId, path.pathId());
            assertEquals(alice, path.peerId());
            assertEquals("TCP", path.transportName());
            assertEquals(ep, path.endpointAddress());
            assertEquals(PathState.CANDIDATE, path.state());
            assertNull(path.connectionId());
            assertTrue(path.isCandidate());
            assertFalse(path.isActive());
            assertTrue(path.optionalConnectionId().isEmpty());
        }

        @Test
        @DisplayName("Active path has connectionId and isActive returns true")
        void testActivePath() {
            ConnectivityPath path = ConnectivityPath.active(pathId, alice, "TCP", ep, "conn-001");

            assertEquals(PathState.ACTIVE, path.state());
            assertEquals("conn-001", path.connectionId());
            assertTrue(path.isActive());
            assertFalse(path.isCandidate());
            assertEquals(Optional.of("conn-001"), path.optionalConnectionId());
        }

        @Test
        @DisplayName("Path activation and deactivation immutably return updated copies")
        void testStateTransitions() {
            ConnectivityPath candidate = ConnectivityPath.candidate(pathId, alice, "TCP", ep);
            assertFalse(candidate.isActive());

            ConnectivityPath active = candidate.activate("conn-002");
            assertTrue(active.isActive());
            assertEquals("conn-002", active.connectionId());
            assertEquals(pathId, active.pathId());

            ConnectivityPath inactive = active.deactivate();
            assertFalse(inactive.isActive());
            assertFalse(inactive.isCandidate());
            assertEquals(PathState.INACTIVE, inactive.state());
            assertNull(inactive.connectionId());
        }

        @Test
        @DisplayName("Active path requires valid non-blank connection ID")
        void testActivePathValidation() {
            assertThrows(NullPointerException.class, () ->
                    ConnectivityPath.active(pathId, alice, "TCP", ep, null));
            assertThrows(IllegalArgumentException.class, () ->
                    ConnectivityPath.active(pathId, alice, "TCP", ep, "  "));
        }
    }

    @Nested
    @DisplayName("PeerConnectivity Tests")
    class PeerConnectivityTests {

        private final PeerId alice = PeerId.of("alice");
        private final PeerId bob = PeerId.of("bob");

        @Test
        @DisplayName("Add, query, and remove paths for a single peer")
        void testPeerConnectivityOperations() {
            PeerConnectivity connectivity = new PeerConnectivity(alice);
            assertEquals(alice, connectivity.peerId());
            assertTrue(connectivity.isEmpty());
            assertEquals(0, connectivity.pathCount());
            assertFalse(connectivity.hasActivePath());

            PathId p1 = PathId.of("p1");
            PathId p2 = PathId.of("p2");

            ConnectivityPath candidate = ConnectivityPath.candidate(p1, alice, "TCP", EndpointAddress.tcp("192.168.1.10", 52132));
            ConnectivityPath active = ConnectivityPath.active(p2, alice, "TCP", EndpointAddress.tcp("192.168.1.11", 52133), "conn-42");

            connectivity.addPath(candidate);
            connectivity.addPath(active);

            assertEquals(2, connectivity.pathCount());
            assertFalse(connectivity.isEmpty());
            assertTrue(connectivity.hasActivePath());

            assertEquals(1, connectivity.activePaths().size());
            assertEquals(active, connectivity.activePaths().get(0));

            assertEquals(1, connectivity.candidatePaths().size());
            assertEquals(candidate, connectivity.candidatePaths().get(0));

            // Lookup
            assertTrue(connectivity.findPath(p1).isPresent());
            assertEquals(candidate, connectivity.findPath(p1).get());

            // Remove
            Optional<ConnectivityPath> removed = connectivity.removePath(p1);
            assertTrue(removed.isPresent());
            assertEquals(candidate, removed.get());
            assertEquals(1, connectivity.pathCount());
            assertTrue(connectivity.findPath(p1).isEmpty());
        }

        @Test
        @DisplayName("Reject paths belonging to a different PeerId")
        void testMismatchedPeerIdRejection() {
            PeerConnectivity connectivity = new PeerConnectivity(alice);
            ConnectivityPath bobsPath = ConnectivityPath.candidate(
                    PathId.of("p-bob"), bob, "TCP", EndpointAddress.tcp("10.0.0.1", 5000)
            );

            assertThrows(IllegalArgumentException.class, () -> connectivity.addPath(bobsPath));
        }

        @Test
        @DisplayName("Peer identity remains independent from path state changes")
        void testIdentityIndependence() {
            PeerConnectivity connectivity = new PeerConnectivity(alice);
            PathId p1 = PathId.of("p1");
            ConnectivityPath path = ConnectivityPath.candidate(p1, alice, "TCP", EndpointAddress.tcp("127.0.0.1", 9000));
            connectivity.addPath(path);

            // Activate path
            connectivity.addPath(path.activate("conn-100"));
            assertEquals(alice, connectivity.peerId());
            assertTrue(connectivity.hasActivePath());

            // Deactivate path
            connectivity.addPath(path.deactivate());
            assertEquals(alice, connectivity.peerId());
            assertFalse(connectivity.hasActivePath());
            assertEquals(1, connectivity.pathCount());
        }
    }
}
