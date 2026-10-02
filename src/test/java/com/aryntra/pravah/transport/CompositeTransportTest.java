package com.aryntra.pravah.transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompositeTransportTest {

    private CompositeTransport composite;
    private MockTransport tcpMock;
    private MockTransport btMock;

    @BeforeEach
    void setUp() {
        tcpMock = new MockTransport("TcpTransport");
        btMock = new MockTransport("BluetoothRfcommTransport");
        composite = new CompositeTransport(tcpMock, btMock);
    }

    @Test
    @DisplayName("S8.4.1: CompositeTransport starts and stops all underlying transports")
    void testLifecycleManagement() {
        assertFalse(composite.isRunning());
        assertFalse(tcpMock.isRunning());
        assertFalse(btMock.isRunning());

        composite.start();
        assertTrue(composite.isRunning());
        assertTrue(tcpMock.isRunning());
        assertTrue(btMock.isRunning());

        composite.stop();
        assertFalse(composite.isRunning());
        assertFalse(tcpMock.isRunning());
        assertFalse(btMock.isRunning());
    }

    @Test
    @DisplayName("S8.4.2: Dispatch send payload to Bluetooth based on bt: prefix")
    void testDispatchBluetoothSend() {
        composite.start();
        byte[] payload = "Hello Bluetooth".getBytes(StandardCharsets.UTF_8);

        composite.send("bt:AA:BB:CC:DD:EE:01", payload);

        assertEquals(0, tcpMock.sentMessages.size());
        assertEquals(1, btMock.sentMessages.size());
        assertEquals("bt:AA:BB:CC:DD:EE:01", btMock.sentMessages.get(0).destination);
        assertArrayEquals(payload, btMock.sentMessages.get(0).payload);
    }

    @Test
    @DisplayName("S8.4.3: Dispatch send payload to TCP based on tcp prefix or fallback")
    void testDispatchTcpSend() {
        composite.start();
        byte[] payload = "Hello TCP".getBytes(StandardCharsets.UTF_8);

        composite.send("/192.168.1.100:54321", payload);

        assertEquals(1, tcpMock.sentMessages.size());
        assertEquals(0, btMock.sentMessages.size());
        assertEquals("/192.168.1.100:54321", tcpMock.sentMessages.get(0).destination);
        assertArrayEquals(payload, tcpMock.sentMessages.get(0).payload);
    }

    @Test
    @DisplayName("S8.4.4: Dynamic connection tracking routes to the transport that opened the connection")
    void testDynamicConnectionTracking() {
        composite.start();

        String customBtId = "custom-link-99";
        btMock.simulateConnectionOpened(customBtId);

        byte[] payload = "Tracked Path".getBytes(StandardCharsets.UTF_8);
        composite.send(customBtId, payload);

        assertEquals(0, tcpMock.sentMessages.size());
        assertEquals(1, btMock.sentMessages.size());
        assertEquals(customBtId, btMock.sentMessages.get(0).destination);
    }

    @Test
    @DisplayName("S8.4.5: Listener aggregates events from all child transports")
    void testListenerAggregation() {
        List<String> opened = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        List<String> received = new ArrayList<>();

        composite.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {
                received.add(senderId + ":" + new String(payload, StandardCharsets.UTF_8));
            }

            @Override
            public void onConnectionOpened(String connectionId) {
                opened.add(connectionId);
            }

            @Override
            public void onConnectionClosed(String connectionId) {
                closed.add(connectionId);
            }
        });

        tcpMock.simulateConnectionOpened("tcp:1");
        tcpMock.simulateDataReceived("tcp:1", "tcp-data".getBytes(StandardCharsets.UTF_8));
        tcpMock.simulateConnectionClosed("tcp:1");

        btMock.simulateConnectionOpened("bt:2");
        btMock.simulateDataReceived("bt:2", "bt-data".getBytes(StandardCharsets.UTF_8));
        btMock.simulateConnectionClosed("bt:2");

        assertEquals(List.of("tcp:1", "bt:2"), opened);
        assertEquals(List.of("tcp:1:tcp-data", "bt:2:bt-data"), received);
        assertEquals(List.of("tcp:1", "bt:2"), closed);
    }

    @Test
    @DisplayName("S8.4.6: Fails safely when sending over stopped composite transport")
    void testSendWhenStoppedThrowsException() {
        assertThrows(IllegalStateException.class, () -> 
            composite.send("bt:AA:BB:CC", new byte[]{1, 2, 3})
        );
    }

    @Test
    @DisplayName("S8.4.7: Capabilities aggregate properties across underlying transports")
    void testAggregatedCapabilities() {
        TransportCapabilities caps = composite.getCapabilities();
        assertTrue(caps.reliable());
        assertTrue(caps.connectionOriented());
        assertTrue(caps.unicast());
    }

    private static class MockTransport implements Transport {
        private final String name;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private TransportListener listener;
        private final List<SentMessage> sentMessages = new ArrayList<>();

        record SentMessage(String destination, byte[] payload) {}

        MockTransport(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void start() {
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void send(String destinationId, byte[] payload) {
            sentMessages.add(new SentMessage(destinationId, payload));
        }

        @Override
        public void setListener(TransportListener listener) {
            this.listener = listener;
        }

        void simulateConnectionOpened(String connId) {
            if (listener != null) listener.onConnectionOpened(connId);
        }

        void simulateDataReceived(String connId, byte[] payload) {
            if (listener != null) listener.onDataReceived(connId, payload);
        }

        void simulateConnectionClosed(String connId) {
            if (listener != null) listener.onConnectionClosed(connId);
        }
    }
}