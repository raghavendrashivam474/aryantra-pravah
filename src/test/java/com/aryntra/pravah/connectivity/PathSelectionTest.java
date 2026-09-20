package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRecord;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.peer.PeerRoutingException;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying S6.5 Path Selection in PeerRouter:
 * - Deterministic selection among active paths
 * - Candidate exclusion
 * - Inactive path exclusion
 * - Explicit failure when no usable path exists
 * - Legacy fallback behavior
 * - Application peer-oriented API transparency
 */
class PathSelectionTest {

    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private RecordingTransport recordingTransport;
    private PeerRouter router;

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        recordingTransport = new RecordingTransport();
        router = new PeerRouter(registry, recordingTransport, connectivityRegistry);
    }

    @Nested
    @DisplayName("Deterministic Active Path Selection")
    class SelectionTests {

        @Test
        @DisplayName("Single active path is selected and routed successfully")
        void singleActivePathSelected() {
            // Setup Bob with one active path
            PathId path1 = PathId.of("path-bob-wifi");
            EndpointAddress ep1 = EndpointAddress.tcp("192.168.1.50", 8080);
            ConnectivityPath activePath = ConnectivityPath.active(path1, bob, "tcp", ep1, "conn-wifi-1");

            connectivityRegistry.registerPath(bob, activePath);

            Message msg = new Message(MessageType.MESSAGE, "alice", "msg-1", "hello".getBytes());
            router.send(bob, msg);

            // Verify transport.send was called with conn-wifi-1
            assertEquals(1, recordingTransport.sentConnectionIds.size());
            assertEquals("conn-wifi-1", recordingTransport.sentConnectionIds.get(0));
        }

        @Test
        @DisplayName("Multiple active paths are selected deterministically by PathId ordering")
        void multipleActivePathsSelectedDeterministically() {
            // Setup Bob with two active paths: "path-z" and "path-a"
            PathId pathZ = PathId.of("path-z-lan");
            PathId pathA = PathId.of("path-a-direct");

            EndpointAddress epZ = EndpointAddress.tcp("192.168.1.50", 8080);
            EndpointAddress epA = EndpointAddress.tcp("10.0.0.5", 9000);

            ConnectivityPath pathObjZ = ConnectivityPath.active(pathZ, bob, "tcp", epZ, "conn-z");
            ConnectivityPath pathObjA = ConnectivityPath.active(pathA, bob, "tcp", epA, "conn-a");

            connectivityRegistry.registerPath(bob, pathObjZ);
            connectivityRegistry.registerPath(bob, pathObjA);

            // Deterministic sort should pick "path-a-direct" -> "conn-a"
            assertEquals("conn-a", router.resolveConnectionId(bob));

            Message msg = new Message(MessageType.MESSAGE, "alice", "msg-2", "hello".getBytes());
            router.send(bob, msg);

            assertEquals(1, recordingTransport.sentConnectionIds.size());
            assertEquals("conn-a", recordingTransport.sentConnectionIds.get(0));
        }

        @Test
        @DisplayName("Inactive paths are excluded from selection")
        void inactivePathsAreExcluded() {
            PathId path1 = PathId.of("path-1-dead");
            PathId path2 = PathId.of("path-2-live");

            EndpointAddress ep1 = EndpointAddress.tcp("192.168.1.50", 8080);
            EndpointAddress ep2 = EndpointAddress.tcp("10.0.0.5", 9000);

            ConnectivityPath deadPath = ConnectivityPath.inactive(path1, bob, "tcp", ep1);
            ConnectivityPath livePath = ConnectivityPath.active(path2, bob, "tcp", ep2, "conn-live");

            connectivityRegistry.registerPath(bob, deadPath);
            connectivityRegistry.registerPath(bob, livePath);

            assertEquals("conn-live", router.resolveConnectionId(bob));
        }

        @Test
        @DisplayName("Candidate paths are NEVER selected for normal message routing")
        void candidatePathsAreNotSelected() {
            PathId candidatePathId = PathId.of("path-candidate");
            EndpointAddress ep = EndpointAddress.tcp("192.168.1.50", 8080);

            ConnectivityPath candidatePath = ConnectivityPath.candidate(candidatePathId, bob, "tcp", ep);
            connectivityRegistry.registerPath(bob, candidatePath);

            // No active path in connectivityRegistry, and no record in registry
            PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.resolveConnectionId(bob));
            assertTrue(ex.getMessage().contains("is not registered"));
        }
    }

    @Nested
    @DisplayName("Fallback and Failure Handling")
    class FallbackAndFailureTests {

        @Test
        @DisplayName("Falls back to legacy PeerRegistry when ConnectivityRegistry has no active paths")
        void fallbackToLegacyRegistryWhenNoActiveConnectivityPaths() {
            // Bob is in PeerRegistry with an active connection, but no ConnectivityPath in PeerConnectivityRegistry
            registry.register(bob, "legacy-conn-bob");

            assertEquals("legacy-conn-bob", router.resolveConnectionId(bob));

            Message msg = new Message(MessageType.MESSAGE, "alice", "msg-fallback", "hello".getBytes());
            router.send(bob, msg);

            assertEquals(1, recordingTransport.sentConnectionIds.size());
            assertEquals("legacy-conn-bob", recordingTransport.sentConnectionIds.get(0));
        }

        @Test
        @DisplayName("Legacy PeerRouter (null connectivityRegistry) functions transparently")
        void legacyRouterFunctionsWithoutConnectivityRegistry() {
            PeerRouter legacyRouter = new PeerRouter(registry, recordingTransport);
            registry.register(bob, "legacy-conn-2");

            assertEquals("legacy-conn-2", legacyRouter.resolveConnectionId(bob));

            Message msg = new Message(MessageType.MESSAGE, "alice", "msg-legacy", "data".getBytes());
            legacyRouter.send(bob, msg);

            assertEquals(1, recordingTransport.sentConnectionIds.size());
            assertEquals("legacy-conn-2", recordingTransport.sentConnectionIds.get(0));
        }

        @Test
        @DisplayName("Disconnected peer in legacy registry produces explicit PeerRoutingException")
        void disconnectedLegacyPeerThrowsException() {
            registry.register(PeerRecord.disconnected(bob));

            PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.resolveConnectionId(bob));
            assertTrue(ex.getMessage().contains("is disconnected"));
        }

        @Test
        @DisplayName("Unregistered peer produces explicit PeerRoutingException")
        void unregisteredPeerThrowsException() {
            PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.resolveConnectionId(bob));
            assertTrue(ex.getMessage().contains("is not registered"));
        }
    }

    /**
     * Minimal in-memory stub recording send invocations.
     */
    private static class RecordingTransport implements Transport {
        final List<String> sentConnectionIds = new ArrayList<>();
        private boolean running = true;
        private TransportListener listener;

        @Override
        public String getName() {
            return "recording-transport";
        }

        @Override
        public void start() {
            this.running = true;
        }

        @Override
        public void stop() {
            this.running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void send(String connectionId, byte[] payload) {
            sentConnectionIds.add(connectionId);
        }

        @Override
        public void setListener(TransportListener listener) {
            this.listener = listener;
        }

        @Override
        public TransportCapabilities getCapabilities() {
            return TransportCapabilities.tcp();
        }
    }
}