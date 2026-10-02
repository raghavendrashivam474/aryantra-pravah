package com.aryntra.pravah.transport.bluetooth;

import com.aryntra.pravah.core.PravahException;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S8.2 - Bluetooth RFCOMM Transport Unit & Contract Tests")
class BluetoothRfcommTransportTest {

    private static final String MAC_A = "AA:BB:CC:DD:EE:01";
    private static final String MAC_B = "AA:BB:CC:DD:EE:02";

    private BluetoothRfcommTransport transportA;
    private BluetoothRfcommTransport transportB;

    @BeforeEach
    void setUp() {
        transportA = new BluetoothRfcommTransport(MAC_A, 1);
        transportB = new BluetoothRfcommTransport(MAC_B, 1);
    }

    @AfterEach
    void tearDown() {
        if (transportA != null) transportA.stop();
        if (transportB != null) transportB.stop();
    }

    @Test
    @DisplayName("Lifecycle: start, stop, isRunning, and idempotency")
    void testLifecycle() {
        assertFalse(transportA.isRunning());
        transportA.start();
        assertTrue(transportA.isRunning());

        // Idempotent start
        transportA.start();
        assertTrue(transportA.isRunning());

        transportA.stop();
        assertFalse(transportA.isRunning());

        // Idempotent stop
        transportA.stop();
        assertFalse(transportA.isRunning());
    }

    @Test
    @DisplayName("Validation: MAC address format and channel range constraints")
    void testValidation() {
        assertThrows(IllegalArgumentException.class, () -> new BluetoothRfcommTransport("invalid-mac", 1));
        assertThrows(IllegalArgumentException.class, () -> new BluetoothRfcommTransport(MAC_A, 0));
        assertThrows(IllegalArgumentException.class, () -> new BluetoothRfcommTransport(MAC_A, 31));
    }

    @Test
    @DisplayName("Capabilities: Validates Bluetooth RFCOMM declared capabilities")
    void testCapabilities() {
        TransportCapabilities caps = transportA.getCapabilities();
        assertTrue(caps.reliable());
        assertTrue(caps.connectionOriented());
        assertTrue(caps.unicast());
        assertFalse(caps.supportsMultiplexing());
        assertEquals("bluetooth", transportA.getName());
    }

    @Test
    @DisplayName("Connectivity: Establish link between two Bluetooth transports")
    void testConnectionEstablishment() throws Exception {
        CountDownLatch openLatchA = new CountDownLatch(1);
        CountDownLatch openLatchB = new CountDownLatch(1);

        transportA.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {}
            @Override
            public void onConnectionOpened(String connectionId) {
                if (connectionId.equals("bt:" + MAC_B)) openLatchA.countDown();
            }
        });

        transportB.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {}
            @Override
            public void onConnectionOpened(String connectionId) {
                if (connectionId.equals("bt:" + MAC_A)) openLatchB.countDown();
            }
        });

        transportA.start();
        transportB.start();

        transportA.connect(MAC_B);

        assertTrue(openLatchA.await(2, TimeUnit.SECONDS), "Transport A should open connection to B");
        assertTrue(openLatchB.await(2, TimeUnit.SECONDS), "Transport B should open connection to A");
        assertEquals(1, transportA.getConnectionCount());
        assertEquals(1, transportB.getConnectionCount());
    }

    @Test
    @DisplayName("Data: Bidirectional data transmission between peers")
    void testBidirectionalDataTransmission() throws Exception {
        CountDownLatch receivedOnB = new CountDownLatch(1);
        CountDownLatch receivedOnA = new CountDownLatch(1);

        AtomicReference<String> payloadOnB = new AtomicReference<>();
        AtomicReference<String> payloadOnA = new AtomicReference<>();

        transportA.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {
                payloadOnA.set(new String(payload, StandardCharsets.UTF_8));
                receivedOnA.countDown();
            }
        });

        transportB.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {
                payloadOnB.set(new String(payload, StandardCharsets.UTF_8));
                receivedOnB.countDown();
            }
        });

        transportA.start();
        transportB.start();
        transportA.connect(MAC_B);

        // A sends to B
        transportA.send("bt:" + MAC_B, "Hello Bluetooth from A".getBytes(StandardCharsets.UTF_8));
        assertTrue(receivedOnB.await(2, TimeUnit.SECONDS), "B should receive message from A");
        assertEquals("Hello Bluetooth from A", payloadOnB.get());

        // B sends to A
        transportB.send("bt:" + MAC_A, "Hello Bluetooth from B".getBytes(StandardCharsets.UTF_8));
        assertTrue(receivedOnA.await(2, TimeUnit.SECONDS), "A should receive reply from B");
        assertEquals("Hello Bluetooth from B", payloadOnA.get());
    }

    @Test
    @DisplayName("Disconnect: Clean disconnection event notification on both sides")
    void testDisconnectNotification() throws Exception {
        CountDownLatch closeLatchB = new CountDownLatch(1);

        transportB.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {}
            @Override
            public void onConnectionClosed(String connectionId) {
                if (connectionId.equals("bt:" + MAC_A)) {
                    closeLatchB.countDown();
                }
            }
        });

        transportA.start();
        transportB.start();
        transportA.connect(MAC_B);

        assertEquals(1, transportA.getConnectionCount());
        assertEquals(1, transportB.getConnectionCount());

        transportA.disconnect("bt:" + MAC_B);

        assertTrue(closeLatchB.await(2, TimeUnit.SECONDS), "B should receive connection closed callback");
        assertEquals(0, transportA.getConnectionCount());
        assertEquals(0, transportB.getConnectionCount());
    }

    @Test
    @DisplayName("Error: Sending to unknown or disconnected destination throws PravahException")
    void testSendToUnknownDestinationThrows() {
        transportA.start();
        assertThrows(PravahException.class, () -> 
                transportA.send("bt:99:99:99:99:99:99", "test".getBytes(StandardCharsets.UTF_8)));
    }
}