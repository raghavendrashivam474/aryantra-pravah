package com.aryntra.pravah.peer;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathId;
import com.aryntra.pravah.connectivity.PathSelectionPolicy;
import com.aryntra.pravah.connectivity.PathState;
import com.aryntra.pravah.connectivity.PeerConnectivity;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.peer.presence.PeerPresence;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.presence.PeerPresenceState;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A.D2.7: Connection Lifecycle & Multi-Transport Transition Stabilization Tests")
public class ConnectionLifecycleStabilizationTest {

    private PeerRegistry registry;
    private PeerPresenceManager presenceManager;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerPresenceBridge presenceBridge;
    private PathSelectionPolicy pathPolicy;
    private MockMultiTransport transport;
    private PeerConnectionCoordinator coordinator;
    private PeerRouter router;

    private static final PeerId LOCAL_PEER = PeerId.of("device-alpha");
    private static final PeerId REMOTE_PEER = PeerId.of("device-beta");

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(2000L);
        connectivityRegistry = new PeerConnectivityRegistry();
        presenceBridge = new PeerPresenceBridge(registry, presenceManager, connectivityRegistry);
        pathPolicy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth", "bt");
        transport = new MockMultiTransport();
        coordinator = new PeerConnectionCoordinator(transport, registry, presenceBridge);
        transport.setListener(coordinator);
        router = new PeerRouter(registry, transport, connectivityRegistry, pathPolicy);
    }

    @Test
    @DisplayName("S1.1 & S1.2: Single-sided connection promotes path to ACTIVE and routes bi-directionally")
    void testSingleSidedConnectionPromotesPathAndRoutes() {
        String tcpConnId = "192.168.1.50:8080";
        transport.simulateConnectionOpened(tcpConnId);

        // Remote peer sends JOIN
        Message joinMsg = new Message(MessageType.JOIN, REMOTE_PEER.value(), "join-beta-1", new byte[0]);
        transport.simulateInboundData(tcpConnId, FrameEncoder.encode(MessageEncoder.encode(joinMsg)));

        // Verify Peer is CONNECTED in presence and registered in registry
        PeerPresence presence = presenceManager.getPresence(REMOTE_PEER);
        assertNotNull(presence);
        assertEquals(PeerPresenceState.CONNECTED, presence.state());

        PeerRecord record = registry.lookup(REMOTE_PEER).orElseThrow();
        assertEquals(tcpConnId, record.connectionId());

        // Verify Connectivity Registry has ACTIVE TCP path
        PeerConnectivity pc = connectivityRegistry.lookup(REMOTE_PEER).orElseThrow();
        assertTrue(pc.hasActivePath());
        List<ConnectivityPath> active = pc.activePaths();
        assertEquals(1, active.size());
        assertEquals("tcp", active.get(0).transportName());

        // Verify router selects this connection for outgoing payload
        Message payload = new Message(MessageType.MESSAGE, LOCAL_PEER.value(), "msg-001", "Hello Beta".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> router.send(REMOTE_PEER, payload));
        assertEquals(1, transport.sentPackets.get(tcpConnId).size());
    }

    @Test
    @DisplayName("S2.2: Simultaneous TCP initiation produces valid multi-path state without map corruption")
    void testSimultaneousTcpInitiationResolution() {
        // Device A opened Socket 1 to B (conn-1)
        String socket1 = "192.168.1.50:8080";
        // Device B opened Socket 2 to A (conn-2)
        String socket2 = "192.168.1.50:54321";

        transport.simulateConnectionOpened(socket1);
        transport.simulateConnectionOpened(socket2);

        // JOIN arrives on Socket 1
        Message join1 = new Message(MessageType.JOIN, REMOTE_PEER.value(), "join-beta-sock1", new byte[0]);
        transport.simulateInboundData(socket1, FrameEncoder.encode(MessageEncoder.encode(join1)));

        // Reciprocal / simultaneous JOIN arrives on Socket 2
        Message join2 = new Message(MessageType.JOIN, REMOTE_PEER.value(), "join-beta-sock2", new byte[0]);
        transport.simulateInboundData(socket2, FrameEncoder.encode(MessageEncoder.encode(join2)));

        // Verify peer remains CONNECTED
        PeerPresence presence = presenceManager.getPresence(REMOTE_PEER);
        assertNotNull(presence);
        assertEquals(PeerPresenceState.CONNECTED, presence.state());

        // Verify both paths are tracked in PeerConnectivity
        PeerConnectivity pc = connectivityRegistry.lookup(REMOTE_PEER).orElseThrow();
        assertEquals(2, pc.activePaths().size());

        // Dispatch message via router - PathSelectionPolicy should pick deterministically
        Message payload = new Message(MessageType.MESSAGE, LOCAL_PEER.value(), "msg-simul", "Ping".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> router.send(REMOTE_PEER, payload));

        // Verify payload dispatched to one of the valid active sockets
        int totalSent = (transport.sentPackets.containsKey(socket1) ? transport.sentPackets.get(socket1).size() : 0)
                + (transport.sentPackets.containsKey(socket2) ? transport.sentPackets.get(socket2).size() : 0);
        assertEquals(1, totalSent);
    }

    @Test
    @DisplayName("S3.1: BT -> TCP Transition switches route to TCP and handles BT drop cleanly")
    void testBluetoothToTcpTransitionAndDrop() {
        String btConnId = "bt:AA:BB:CC:DD:EE:FF";
        transport.simulateConnectionOpened(btConnId);

        // Initial state: BT ACTIVE
        Message btJoin = new Message(MessageType.JOIN, REMOTE_PEER.value(), "join-beta-bt", new byte[0]);
        transport.simulateInboundData(btConnId, FrameEncoder.encode(MessageEncoder.encode(btJoin)));

        PeerConnectivity pc = connectivityRegistry.lookup(REMOTE_PEER).orElseThrow();
        assertEquals(1, pc.activePaths().size());
        assertEquals("bluetooth", pc.activePaths().get(0).transportName());

        // Outgoing message routes via BT
        Message msg1 = new Message(MessageType.MESSAGE, LOCAL_PEER.value(), "m1", "Via BT".getBytes(StandardCharsets.UTF_8));
        router.send(REMOTE_PEER, msg1);
        assertEquals(1, transport.sentPackets.get(btConnId).size());

        // Now establish TCP
        String tcpConnId = "192.168.1.100:8080";
        transport.simulateConnectionOpened(tcpConnId);
        Message tcpJoin = new Message(MessageType.JOIN, REMOTE_PEER.value(), "join-beta-tcp", new byte[0]);
        transport.simulateInboundData(tcpConnId, FrameEncoder.encode(MessageEncoder.encode(tcpJoin)));

        // Verify both are active in PeerConnectivity
        assertEquals(2, pc.activePaths().size());

        // PathSelectionPolicy prefers TCP over BT -> Next message must go via TCP
        Message msg2 = new Message(MessageType.MESSAGE, LOCAL_PEER.value(), "m2", "Via TCP".getBytes(StandardCharsets.UTF_8));
        router.send(REMOTE_PEER, msg2);
        assertEquals(1, transport.sentPackets.get(tcpConnId).size());

        // Now drop Bluetooth connection
        transport.simulateConnectionClosed(btConnId);

        // Verify peer remains connected on TCP
        PeerPresence presence = presenceManager.getPresence(REMOTE_PEER);
        assertNotNull(presence);
        assertEquals(PeerPresenceState.CONNECTED, presence.state());

        assertEquals(1, pc.activePaths().size());
        assertEquals("tcp", pc.activePaths().get(0).transportName());

        // Subsequent message continues over TCP seamlessly
        Message msg3 = new Message(MessageType.MESSAGE, LOCAL_PEER.value(), "m3", "Still on TCP".getBytes(StandardCharsets.UTF_8));
        router.send(REMOTE_PEER, msg3);
        assertEquals(2, transport.sentPackets.get(tcpConnId).size());
    }

    @Test
    @DisplayName("S4: Reciprocal JOIN idempotency prevents path duplication")
    void testReciprocalJoinIdempotency() {
        String tcpConnId = "192.168.1.50:8080";
        transport.simulateConnectionOpened(tcpConnId);

        // Simulate 3 successive JOIN messages over the same connection
        for (int i = 1; i <= 3; i++) {
            Message join = new Message(MessageType.JOIN, REMOTE_PEER.value(), "join-" + i, new byte[0]);
            transport.simulateInboundData(tcpConnId, FrameEncoder.encode(MessageEncoder.encode(join)));
        }

        PeerConnectivity pc = connectivityRegistry.lookup(REMOTE_PEER).orElseThrow();
        assertEquals(1, pc.activePaths().size(), "Must not create duplicate paths for same connection ID");
        
        PeerPresence presence = presenceManager.getPresence(REMOTE_PEER);
        assertNotNull(presence);
        assertEquals(PeerPresenceState.CONNECTED, presence.state());
    }

    // --- Mock Multi-Transport for deterministic lifecycle simulation ---
    private static class MockMultiTransport implements Transport {
        private TransportListener listener;
        final Map<String, List<byte[]>> sentPackets = new ConcurrentHashMap<>();
        private boolean running = true;

        @Override
        public String getName() {
            return "MockMultiTransport";
        }

        @Override
        public void start() { running = true; }

        @Override
        public void stop() { running = false; }

        @Override
        public boolean isRunning() { return running; }

        @Override
        public void send(String destinationId, byte[] payload) {
            sentPackets.computeIfAbsent(destinationId, k -> new ArrayList<>()).add(payload);
        }

        @Override
        public void setListener(TransportListener listener) {
            this.listener = listener;
        }

        void simulateConnectionOpened(String connId) {
            if (listener != null) listener.onConnectionOpened(connId);
        }

        void simulateInboundData(String connId, byte[] data) {
            if (listener != null) listener.onDataReceived(connId, data);
        }

        void simulateConnectionClosed(String connId) {
            if (listener != null) listener.onConnectionClosed(connId);
        }
    }
}