package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.peer.PeerRoutingException;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.CompositeTransport;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S8.5 - Multi-Path Operation & Path Selection Policy Tests")
class MultiPathOperationTest {

    private final PeerId alice = PeerId.of("peer-alice");
    private final PeerId bob = PeerId.of("peer-bob");

    private PeerRegistry peerRegistry;
    private PeerConnectivityRegistry connectivityRegistry;
    private MockTransport mockTcp;
    private MockTransport mockBt;
    private CompositeTransport compositeTransport;

    @BeforeEach
    void setUp() {
        peerRegistry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        mockTcp = new MockTransport("TcpTransport");
        mockBt = new MockTransport("BluetoothRfcommTransport");
        compositeTransport = new CompositeTransport(mockTcp, mockBt);
        compositeTransport.start();
    }

    @Test
    @DisplayName("S8.5.1: Peer represents simultaneous TCP and Bluetooth ACTIVE paths without duplicating peer")
    void testSimultaneousActivePathsSinglePeer() {
        EndpointAddress tcpEndpoint = EndpointAddress.tcp("192.168.1.50", 8080);
        EndpointAddress btEndpoint = EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:02", 1);

        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("path-1-tcp"), bob, "tcp", tcpEndpoint, "192.168.1.50:8080"
        );
        ConnectivityPath btPath = ConnectivityPath.active(
                PathId.of("path-2-bt"), bob, "bluetooth", btEndpoint, "bt:AA:BB:CC:DD:EE:02"
        );

        connectivityRegistry.registerPath(bob, tcpPath);
        connectivityRegistry.registerPath(bob, btPath);

        PeerConnectivity conn = connectivityRegistry.lookup(bob).orElseThrow();
        assertEquals(bob, conn.peerId());
        assertEquals(2, conn.pathCount());
        assertEquals(2, conn.activePaths().size());
        assertEquals(1, connectivityRegistry.size(), "Only one PeerConnectivity entity should exist in registry");
    }

    @Test
    @DisplayName("S8.5.2: PathSelectionPolicy.preferSchemes prefers TCP over Bluetooth deterministically")
    void testPrioritizedPathSelectionTcpOverBluetooth() {
        EndpointAddress tcpEndpoint = EndpointAddress.tcp("192.168.1.50", 8080);
        EndpointAddress btEndpoint = EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:02", 1);

        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("path-tcp"), bob, "tcp", tcpEndpoint, "192.168.1.50:8080"
        );
        ConnectivityPath btPath = ConnectivityPath.active(
                PathId.of("path-bt"), bob, "bluetooth", btEndpoint, "bt:AA:BB:CC:DD:EE:02"
        );

        connectivityRegistry.registerPath(bob, btPath);
        connectivityRegistry.registerPath(bob, tcpPath);

        // Router configured with preference: TCP > Bluetooth
        PathSelectionPolicy policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        PeerRouter router = new PeerRouter(peerRegistry, compositeTransport, connectivityRegistry, policy);

        // TCP path should be selected
        assertEquals("192.168.1.50:8080", router.resolveConnectionId(bob));

        router.send(alice, bob, "m-1", "TCP payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, mockTcp.sentDestinations.size());
        assertEquals(0, mockBt.sentDestinations.size());
    }

    @Test
    @DisplayName("S8.5.3: Path failover switches to Bluetooth when TCP path becomes INACTIVE")
    void testPathFailoverOnTcpDeactivation() {
        EndpointAddress tcpEndpoint = EndpointAddress.tcp("192.168.1.50", 8080);
        EndpointAddress btEndpoint = EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:02", 1);

        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("path-tcp"), bob, "tcp", tcpEndpoint, "192.168.1.50:8080"
        );
        ConnectivityPath btPath = ConnectivityPath.active(
                PathId.of("path-bt"), bob, "bluetooth", btEndpoint, "bt:AA:BB:CC:DD:EE:02"
        );

        connectivityRegistry.registerPath(bob, tcpPath);
        connectivityRegistry.registerPath(bob, btPath);

        PathSelectionPolicy policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        PeerRouter router = new PeerRouter(peerRegistry, compositeTransport, connectivityRegistry, policy);

        // Initial send goes to TCP
        router.send(alice, bob, "m-1", "msg 1".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, mockTcp.sentDestinations.size());
        assertEquals(0, mockBt.sentDestinations.size());

        // Simulate TCP path failure: deactivate TCP path
        connectivityRegistry.getOrCreate(bob).addPath(tcpPath.deactivate());

        // Subsequent send automatically routes over Bluetooth
        assertEquals("bt:AA:BB:CC:DD:EE:02", router.resolveConnectionId(bob));
        router.send(alice, bob, "m-2", "msg 2".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, mockTcp.sentDestinations.size(), "TCP should have no new messages");
        assertEquals(1, mockBt.sentDestinations.size(), "BT should receive second message");
        assertEquals("bt:AA:BB:CC:DD:EE:02", mockBt.sentDestinations.get(0));
    }

    @Test
    @DisplayName("S8.5.4: Path recovery re-promotes TCP when it returns to ACTIVE state")
    void testPathRecoveryRePromotesPrimaryScheme() {
        EndpointAddress tcpEndpoint = EndpointAddress.tcp("192.168.1.50", 8080);
        EndpointAddress btEndpoint = EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:02", 1);

        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("path-tcp"), bob, "tcp", tcpEndpoint, "192.168.1.50:8080"
        );
        ConnectivityPath btPath = ConnectivityPath.active(
                PathId.of("path-bt"), bob, "bluetooth", btEndpoint, "bt:AA:BB:CC:DD:EE:02"
        );

        connectivityRegistry.registerPath(bob, tcpPath);
        connectivityRegistry.registerPath(bob, btPath);

        PathSelectionPolicy policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        PeerRouter router = new PeerRouter(peerRegistry, compositeTransport, connectivityRegistry, policy);

        // Deactivate TCP -> falls back to BT
        connectivityRegistry.getOrCreate(bob).addPath(tcpPath.deactivate());
        assertEquals("bt:AA:BB:CC:DD:EE:02", router.resolveConnectionId(bob));

        // Reactivate TCP -> re-promotes TCP
        connectivityRegistry.getOrCreate(bob).addPath(tcpPath.activate("192.168.1.50:8080"));
        assertEquals("192.168.1.50:8080", router.resolveConnectionId(bob));
    }

    @Test
    @DisplayName("S8.5.5: Explicit PeerRoutingException when all paths fail and no fallback is available")
    void testAllPathsFailedThrowsExplicitException() {
        EndpointAddress tcpEndpoint = EndpointAddress.tcp("192.168.1.50", 8080);
        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("path-tcp"), bob, "tcp", tcpEndpoint, "192.168.1.50:8080"
        );

        connectivityRegistry.registerPath(bob, tcpPath);
        connectivityRegistry.getOrCreate(bob).addPath(tcpPath.deactivate());

        PeerRouter router = new PeerRouter(peerRegistry, compositeTransport, connectivityRegistry);

        assertThrows(PeerRoutingException.class, () -> router.resolveConnectionId(bob));
    }

    private static class MockTransport implements Transport {
        private final String name;
        private boolean running = true;
        final List<String> sentDestinations = new ArrayList<>();

        MockTransport(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void send(String destinationId, byte[] payload) {
            sentDestinations.add(destinationId);
        }

        @Override
        public void setListener(TransportListener listener) {}

        @Override
        public TransportCapabilities getCapabilities() {
            return TransportCapabilities.tcp();
        }
    }
}