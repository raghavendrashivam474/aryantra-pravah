package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B.R1 — Runtime Path Failover & Lifecycle Tests.
 */
class RuntimePathFailoverTest {

    private PeerRegistry registry;
    private PeerPresenceManager presenceManager;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerPresenceBridge bridge;
    private PeerId peer;

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(5000L);
        connectivityRegistry = new PeerConnectivityRegistry();
        bridge = new PeerPresenceBridge(registry, presenceManager, connectivityRegistry);
        peer = PeerId.of("test-peer");
    }

    @Test
    @DisplayName("B.R1-TEST-1: Deactivating TCP path via bridge preserves Bluetooth path")
    void singlePathFailureMustNotKillOtherPaths() {
        PathId tcpPathId = PathId.of("path:tcp:1");
        PathId btPathId = PathId.of("path:bt:1");

        ConnectivityPath tcpPath = ConnectivityPath.active(
                tcpPathId, peer, "tcp",
                EndpointAddress.tcp("192.168.1.5", 8080), "tcp-conn-1"
        );
        ConnectivityPath btPath = ConnectivityPath.active(
                btPathId, peer, "bluetooth",
                EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:01", 1), "bt:AA:BB:CC:DD:EE:01"
        );

        connectivityRegistry.registerPath(peer, tcpPath);
        connectivityRegistry.registerPath(peer, btPath);

        assertEquals(2, connectivityRegistry.getOrCreate(peer).activePaths().size(),
                "Both paths should be active initially");

        // Surgical disconnect of TCP path only
        bridge.handleConnectionClosed(peer, "tcp-conn-1");

        PeerConnectivity after = connectivityRegistry.getOrCreate(peer);
        assertEquals(1, after.activePaths().size(),
                "Bluetooth path must remain ACTIVE after TCP disconnect");
        assertEquals("bluetooth", after.activePaths().get(0).transportName());
    }

    @Test
    @DisplayName("B.R1-TEST-2: Coordinator onConnectionClosed must not wipe out alternate active paths")
    void coordinatorConnectionClosedMustPreserveOtherPaths() {
        AtomicReference<TransportListener> listenerRef = new AtomicReference<>();
        Transport mockTransport = new Transport() {
            @Override public String getName() { return "mock"; }
            @Override public void start() {}
            @Override public void stop() {}
            @Override public boolean isRunning() { return true; }
            @Override public void send(String dest, byte[] payload) {}
            @Override public void setListener(TransportListener l) { listenerRef.set(l); }
            @Override public TransportCapabilities getCapabilities() { return TransportCapabilities.defaultCapabilities(); }
        };

        PeerConnectionCoordinator coordinator = new PeerConnectionCoordinator(mockTransport, registry, bridge);

        // Authenticate peer via TCP JOIN
        Message joinTcp = new Message(MessageType.JOIN, peer.value(), "join-1", new byte[0]);
        byte[] framedJoinTcp = FrameEncoder.encode(MessageEncoder.encode(joinTcp));
        coordinator.onConnectionOpened("tcp-conn-1");
        coordinator.onDataReceived("tcp-conn-1", framedJoinTcp);

        // Also authenticate same peer via BT JOIN
        Message joinBt = new Message(MessageType.JOIN, peer.value(), "join-2", new byte[0]);
        byte[] framedJoinBt = FrameEncoder.encode(MessageEncoder.encode(joinBt));
        coordinator.onConnectionOpened("bt:AA:BB:CC:DD:EE:01");
        coordinator.onDataReceived("bt:AA:BB:CC:DD:EE:01", framedJoinBt);

        // Verify 2 active paths
        assertEquals(2, connectivityRegistry.getOrCreate(peer).activePaths().size(),
                "Peer should have 2 active paths after TCP and BT JOINs");

        // Simulate TCP socket drop via transport callback
        coordinator.onConnectionClosed("tcp-conn-1");

        // BT path must still be ACTIVE
        PeerConnectivity conn = connectivityRegistry.getOrCreate(peer);
        assertEquals(1, conn.activePaths().size(),
                "BT path must remain ACTIVE after TCP connection closes in coordinator");
        assertEquals("bluetooth", conn.activePaths().get(0).transportName());
    }

    @Test
    @DisplayName("B.R1-TEST-3: PathSelectionPolicy selects BT when TCP is inactive")
    void selectionPolicyPrefersActiveBluetoothWhenTcpIsInactive() {
        ConnectivityPath deadTcp = ConnectivityPath.inactive(
                PathId.of("dead-tcp"), peer, "tcp",
                EndpointAddress.tcp("10.0.0.3", 6060)
        );
        ConnectivityPath liveBt = ConnectivityPath.active(
                PathId.of("live-bt"), peer, "bluetooth",
                EndpointAddress.of("bluetooth", "FF:EE:DD:CC:BB:AA", 1), "bt:FF:EE:DD:CC:BB:AA"
        );

        connectivityRegistry.registerPath(peer, deadTcp);
        connectivityRegistry.registerPath(peer, liveBt);

        PathSelectionPolicy policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        var selected = policy.selectPath(peer, connectivityRegistry.getOrCreate(peer).activePaths());

        assertTrue(selected.isPresent(), "Should select the live BT path");
        assertEquals("bluetooth", selected.get().transportName());
    }
}