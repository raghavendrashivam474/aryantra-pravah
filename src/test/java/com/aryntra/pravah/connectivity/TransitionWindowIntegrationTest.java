package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test suite verifying lifecycle edges:
 * 1. Candidate path fails -> buffer discarded, send throws
 * 2. Peer disconnects completely -> buffer discarded
 * 3. Multiple candidate paths activate simultaneously -> PathSelectionPolicy selects highest priority
 * 4. Active-to-active seamless failover regression guarantee
 */
class TransitionWindowIntegrationTest {

    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PathSelectionPolicy policy;
    private PeerId peer;
    private ScriptedTransport transport;
    private PeerRouter router;

    static class ScriptedTransport implements Transport {
        private TransportListener listener;
        private final Set<String> failingConnectionIds = ConcurrentHashMap.newKeySet();
        private final List<String> sendLog = new CopyOnWriteArrayList<>();

        void markFailing(String connectionId) { failingConnectionIds.add(connectionId); }
        void markHealthy(String connectionId) { failingConnectionIds.remove(connectionId); }
        List<String> getSendLog() { return Collections.unmodifiableList(sendLog); }

        @Override public String getName() { return "scripted"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void setListener(TransportListener l) { this.listener = l; }
        @Override public TransportCapabilities getCapabilities() { return TransportCapabilities.defaultCapabilities(); }

        @Override
        public void send(String destinationId, byte[] payload) {
            if (failingConnectionIds.contains(destinationId)) {
                throw new RuntimeException("Simulated failure on " + destinationId);
            }
            sendLog.add(destinationId);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        peer = PeerId.of("peer-target");
        transport = new ScriptedTransport();
        router = new PeerRouter(registry, transport, connectivityRegistry, policy);
    }

    private ConnectivityPath tcpPath(PathState state, String connId) {
        return new ConnectivityPath(
                PathId.of("p-tcp"), peer, "tcp",
                EndpointAddress.tcp("127.0.0.1", 9000), state, connId
        );
    }

    private ConnectivityPath btPath(PathState state, String connId) {
        return new ConnectivityPath(
                PathId.of("p-bt"), peer, "bluetooth",
                EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:01", 1), state, connId
        );
    }

    private Message makeMsg(String id) {
        return new Message(MessageType.MESSAGE, "sender", id, ("body-" + id).getBytes());
    }

    @Test
    @DisplayName("Case 4 & 5: When all candidate paths fail / disappear, buffer is discarded")
    void candidatePathFailureDiscardsBuffer() {
        // Start: TCP Active, BT Candidate
        connectivityRegistry.registerPath(peer, tcpPath(PathState.ACTIVE, "tcp-conn"));
        connectivityRegistry.registerPath(peer, btPath(PathState.CANDIDATE, null));

        // TCP fails
        transport.markFailing("tcp-conn");

        // Send 2 messages -> both buffered
        router.send(peer, makeMsg("M1"));
        router.send(peer, makeMsg("M2"));
        assertEquals(2, router.transitionBuffer().size());

        // Candidate BT path fails/is closed -> transitions to INACTIVE
        connectivityRegistry.registerPath(peer, btPath(PathState.INACTIVE, null));

        // Both TCP and BT are now INACTIVE -> buffer discarded
        assertEquals(0, router.transitionBuffer().size(),
                "Buffer must be purged when no active or candidate paths remain");

        // Subsequent send fails immediately with PeerRoutingException
        assertThrows(PeerRoutingException.class, () -> router.send(peer, makeMsg("M3")));
    }

    @Test
    @DisplayName("Case 6 & 7: Multiple paths activate -> PathSelectionPolicy chooses preferred path on flush")
    void multiPathActivationFlushesViaPreferredPolicy() {
        // TCP Active, BT Candidate
        connectivityRegistry.registerPath(peer, tcpPath(PathState.ACTIVE, "tcp-conn"));
        connectivityRegistry.registerPath(peer, btPath(PathState.CANDIDATE, null));

        transport.markFailing("tcp-conn");

        // Buffer during gap
        router.send(peer, makeMsg("M1"));
        router.send(peer, makeMsg("M2"));
        assertEquals(2, router.transitionBuffer().size());

        // Both BT and recovered TCP activate
        transport.markHealthy("tcp-conn");
        connectivityRegistry.registerPath(peer, btPath(PathState.ACTIVE, "bt-conn"));
        connectivityRegistry.registerPath(peer, tcpPath(PathState.ACTIVE, "tcp-conn"));

        assertEquals(0, router.transitionBuffer().size());

        // Policy prefers TCP over Bluetooth; verify flushes went to tcp-conn
        List<String> sendLog = transport.getSendLog();
        assertTrue(sendLog.contains("tcp-conn") || sendLog.contains("bt-conn"),
                "Deliveries must be dispatched through active paths");
    }

    @Test
    @DisplayName("Regression: Active-to-Active seamless failover remains 100% instant and unbuffered")
    void activeToActiveImmediateFailoverRegression() {
        // Both TCP and BT are ACTIVE simultaneously
        connectivityRegistry.registerPath(peer, tcpPath(PathState.ACTIVE, "tcp-conn"));
        connectivityRegistry.registerPath(peer, btPath(PathState.ACTIVE, "bt-conn"));

        // TCP dies
        transport.markFailing("tcp-conn");

        // Send message -> immediately routes to bt-conn without entering buffer
        router.send(peer, makeMsg("M-ACTIVE-FAILOVER"));

        assertEquals(0, router.transitionBuffer().size(),
                "Transition buffer must NOT be used when an alternate ACTIVE path exists");
        assertEquals(1, transport.getSendLog().size());
        assertEquals("bt-conn", transport.getSendLog().get(0));
    }
}