package com.aryntra.pravah.peer;

import com.aryntra.pravah.connectivity.*;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B.R1 — Router Failover & Transition Tests.
 */
class RouterFailoverTest {

    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerId peer;

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        peer = PeerId.of("failover-peer");
    }

    @Test
    @DisplayName("B.R1-TEST-4: Router fails over to secondary path when primary transport send throws")
    void routerShouldFailoverOnTransportException() {
        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("tcp-primary"), peer, "tcp",
                EndpointAddress.tcp("10.0.0.1", 8080), "tcp-conn-1"
        );
        ConnectivityPath btPath = ConnectivityPath.active(
                PathId.of("bt-secondary"), peer, "bluetooth",
                EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:01", 1), "bt:AA:BB:CC:DD:EE:01"
        );
        connectivityRegistry.registerPath(peer, tcpPath);
        connectivityRegistry.registerPath(peer, btPath);
        registry.register(peer, "tcp-conn-1");

        List<String> sendLog = new ArrayList<>();
        Transport flakyTransport = new Transport() {
            private TransportListener listener;

            @Override public String getName() { return "flaky"; }
            @Override public void start() {}
            @Override public void stop() {}
            @Override public boolean isRunning() { return true; }
            @Override public void setListener(TransportListener l) { this.listener = l; }
            @Override public TransportCapabilities getCapabilities() {
                return TransportCapabilities.defaultCapabilities();
            }

            @Override
            public void send(String destinationId, byte[] payload) {
                sendLog.add(destinationId);
                if ("tcp-conn-1".equals(destinationId)) {
                    throw new RuntimeException("Simulated TCP socket write error");
                }
                // Bluetooth send succeeds
            }
        };

        PathSelectionPolicy policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        PeerRouter router = new PeerRouter(registry, flakyTransport, connectivityRegistry, policy);

        Message msg = new Message(MessageType.MESSAGE, "local", "msg-1", "hello".getBytes());

        // Send should automatically fail over to BT path instead of blowing up
        assertDoesNotThrow(() -> router.send(peer, msg));

        // Verify TCP was attempted first, then BT was used
        assertEquals(2, sendLog.size(), "Should have attempted TCP then failed over to BT");
        assertEquals("tcp-conn-1", sendLog.get(0));
        assertEquals("bt:AA:BB:CC:DD:EE:01", sendLog.get(1));
    }
}