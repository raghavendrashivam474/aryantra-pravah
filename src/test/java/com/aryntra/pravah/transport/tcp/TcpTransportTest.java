package com.aryntra.pravah.transport.tcp;

import com.aryntra.pravah.core.PravahException;
import com.aryntra.pravah.transport.Transport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class TcpTransportTest {

    private TcpTransport serverTransport;
    private TcpTransport clientTransport;
    private final List<TcpTransport> extraClients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (serverTransport != null) {
            serverTransport.stop();
        }
        if (clientTransport != null) {
            clientTransport.stop();
        }
        for (TcpTransport client : extraClients) {
            if (client != null) {
                client.stop();
            }
        }
        extraClients.clear();
    }

    // ==========================================
    // S1.1 - TCP Foundation Tests
    // ==========================================

    @Test
    @DisplayName("S1.1: Server socket binds, accepts connection, and stops cleanly")
    void testTcpFoundationLifecycle() throws Exception {
        serverTransport = new TcpTransport(0);
        assertFalse(serverTransport.isRunning());

        serverTransport.start();
        assertTrue(serverTransport.isRunning());
        int boundPort = serverTransport.getBoundPort();
        assertTrue(boundPort > 0);

        CountDownLatch dataLatch = new CountDownLatch(1);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();

        serverTransport.setListener((senderId, payload) -> {
            receivedData.set(payload);
            dataLatch.countDown();
        });

        // Direct standard java.net.Socket client
        try (Socket rawClient = new Socket("127.0.0.1", boundPort)) {
            OutputStream out = rawClient.getOutputStream();
            byte[] msg = "S1.1 Foundation Ping".getBytes(StandardCharsets.UTF_8);
            out.write(msg);
            out.flush();

            assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
            assertArrayEquals(msg, receivedData.get());
        }

        serverTransport.stop();
        assertFalse(serverTransport.isRunning());
    }

    // ==========================================
    // S1.2 - Transport Contract Integration Tests
    // ==========================================

    @Test
    @DisplayName("S1.2: TcpTransport satisfies Transport contract polymorphically")
    void testTransportContractPolymorphism() {
        Transport transport = new TcpTransport(0);
        assertEquals("tcp", transport.getName());
        assertFalse(transport.isRunning());

        transport.start();
        assertTrue(transport.isRunning());

        transport.stop();
        assertFalse(transport.isRunning());
    }

    @Test
    @DisplayName("S1.2: Sending without running or active connection throws PravahException")
    void testSendValidation() {
        TcpTransport transport = new TcpTransport(0);
        assertThrows(PravahException.class, () -> transport.send("peer", new byte[]{1, 2, 3}));

        transport.start();
        assertThrows(PravahException.class, () -> transport.send("peer", new byte[]{1, 2, 3}));
        transport.stop();
    }

    // ==========================================
    // S1.3 - Bidirectional Communication Tests
    // ==========================================

    @Test
    @DisplayName("S1.3: Bidirectional communication between Peer A and Peer B")
    void testBidirectionalCommunication() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        clientTransport = new TcpTransport(0);
        clientTransport.start();

        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch clientReceivedLatch = new CountDownLatch(1);

        AtomicReference<byte[]> serverReceivedPayload = new AtomicReference<>();
        AtomicReference<byte[]> clientReceivedPayload = new AtomicReference<>();

        serverTransport.setListener((senderId, payload) -> {
            serverReceivedPayload.set(payload);
            serverReceivedLatch.countDown();
        });

        clientTransport.setListener((senderId, payload) -> {
            clientReceivedPayload.set(payload);
            clientReceivedLatch.countDown();
        });

        clientTransport.connect("127.0.0.1", serverPort);

        waitForCondition(() -> serverTransport.isConnected(), 2000);
        assertTrue(serverTransport.isConnected(), "Server should have connected client");

        // Peer A (Client) -> Peer B (Server)
        byte[] clientToServerMsg = "Hello from Client".getBytes(StandardCharsets.UTF_8);
        clientTransport.send("server", clientToServerMsg);
        assertTrue(serverReceivedLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(clientToServerMsg, serverReceivedPayload.get());

        // Peer B (Server) -> Peer A (Client)
        byte[] serverToClientMsg = "Hello back from Server".getBytes(StandardCharsets.UTF_8);
        serverTransport.send("client", serverToClientMsg);
        assertTrue(clientReceivedLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(serverToClientMsg, clientReceivedPayload.get());
    }

    @Test
    @DisplayName("S1.3: Multiple sequential payloads in both directions")
    void testMultiplePayloads() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        clientTransport = new TcpTransport(0);
        clientTransport.start();

        int messageCount = 5;
        CountDownLatch serverLatch = new CountDownLatch(messageCount);
        List<String> serverReceived = Collections.synchronizedList(new ArrayList<>());

        serverTransport.setListener((senderId, payload) -> {
            serverReceived.add(new String(payload, StandardCharsets.UTF_8));
            serverLatch.countDown();
        });

        clientTransport.connect("127.0.0.1", serverPort);
        waitForCondition(() -> serverTransport.isConnected(), 2000);

        for (int i = 1; i <= messageCount; i++) {
            clientTransport.send("server", ("Message #" + i).getBytes(StandardCharsets.UTF_8));
            Thread.sleep(20);
        }

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(messageCount, serverReceived.size());
        for (int i = 1; i <= messageCount; i++) {
            assertEquals("Message #" + i, serverReceived.get(i - 1));
        }
    }

    @Test
    @DisplayName("S1.3: Empty payload transmission is handled")
    void testEmptyPayload() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        clientTransport = new TcpTransport(0);
        clientTransport.start();

        clientTransport.connect("127.0.0.1", serverPort);

        assertDoesNotThrow(() -> clientTransport.send("server", new byte[0]));
    }

    @Test
    @DisplayName("S1.3: Disconnect handling and clean shutdown")
    void testDisconnectAndCleanShutdown() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        clientTransport = new TcpTransport(0);
        clientTransport.start();

        clientTransport.connect("127.0.0.1", serverPort);
        waitForCondition(() -> serverTransport.isConnected(), 2000);
        assertTrue(clientTransport.isConnected());

        // Stop client side
        clientTransport.stop();
        assertFalse(clientTransport.isRunning());

        // Wait for server to detect disconnection
        waitForCondition(() -> !serverTransport.isConnected(), 2000);
        assertFalse(serverTransport.isConnected());

        // Stop server side cleanly
        serverTransport.stop();
        assertFalse(serverTransport.isRunning());
    }

    // ==========================================
    // S1.4 - Concurrent Connections Tests
    // ==========================================

    @Test
    @DisplayName("S1.4: Multiple clients connect simultaneously and server maintains all connections")
    void testMultipleClientsConnect() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        TcpTransport clientA = createAndStartClient();
        TcpTransport clientB = createAndStartClient();
        TcpTransport clientC = createAndStartClient();

        clientA.connect("127.0.0.1", serverPort);
        clientB.connect("127.0.0.1", serverPort);
        clientC.connect("127.0.0.1", serverPort);

        waitForCondition(() -> serverTransport.getConnectionCount() == 3, 3000);
        assertEquals(3, serverTransport.getConnectionCount(), "Server should have 3 concurrent active connections");
        assertTrue(clientA.isConnected());
        assertTrue(clientB.isConnected());
        assertTrue(clientC.isConnected());
    }

    @Test
    @DisplayName("S1.4: Independent concurrent reception from multiple clients")
    void testIndependentConcurrentReception() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        ConcurrentHashMap<String, String> receivedMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(3);

        serverTransport.setListener((senderId, payload) -> {
            receivedMap.put(new String(payload, StandardCharsets.UTF_8), senderId);
            latch.countDown();
        });

        TcpTransport clientA = createAndStartClient();
        TcpTransport clientB = createAndStartClient();
        TcpTransport clientC = createAndStartClient();

        clientA.connect("127.0.0.1", serverPort);
        clientB.connect("127.0.0.1", serverPort);
        clientC.connect("127.0.0.1", serverPort);

        waitForCondition(() -> serverTransport.getConnectionCount() == 3, 3000);

        clientA.send("server", "PAYLOAD_A".getBytes(StandardCharsets.UTF_8));
        clientB.send("server", "PAYLOAD_B".getBytes(StandardCharsets.UTF_8));
        clientC.send("server", "PAYLOAD_C".getBytes(StandardCharsets.UTF_8));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(receivedMap.containsKey("PAYLOAD_A"));
        assertTrue(receivedMap.containsKey("PAYLOAD_B"));
        assertTrue(receivedMap.containsKey("PAYLOAD_C"));
    }

    @Test
    @DisplayName("S1.4: Destination-aware isolated sending to specific peers")
    void testDestinationAwareSending() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        ConcurrentHashMap<String, String> clientSenderMap = new ConcurrentHashMap<>();
        CountDownLatch clientIdentificationLatch = new CountDownLatch(2);

        serverTransport.setListener((senderId, payload) -> {
            String clientName = new String(payload, StandardCharsets.UTF_8);
            clientSenderMap.put(clientName, senderId);
            clientIdentificationLatch.countDown();
        });

        TcpTransport clientA = createAndStartClient();
        TcpTransport clientB = createAndStartClient();

        CountDownLatch clientAReceivedLatch = new CountDownLatch(1);
        CountDownLatch clientBReceivedLatch = new CountDownLatch(1);
        AtomicReference<String> clientAReceivedMsg = new AtomicReference<>();
        AtomicReference<String> clientBReceivedMsg = new AtomicReference<>();

        clientA.setListener((senderId, payload) -> {
            clientAReceivedMsg.set(new String(payload, StandardCharsets.UTF_8));
            clientAReceivedLatch.countDown();
        });

        clientB.setListener((senderId, payload) -> {
            clientBReceivedMsg.set(new String(payload, StandardCharsets.UTF_8));
            clientBReceivedLatch.countDown();
        });

        clientA.connect("127.0.0.1", serverPort);
        clientB.connect("127.0.0.1", serverPort);

        // Identify who is who
        clientA.send("server", "CLIENT_A".getBytes(StandardCharsets.UTF_8));
        clientB.send("server", "CLIENT_B".getBytes(StandardCharsets.UTF_8));
        assertTrue(clientIdentificationLatch.await(5, TimeUnit.SECONDS));

        String senderIdA = clientSenderMap.get("CLIENT_A");
        String senderIdB = clientSenderMap.get("CLIENT_B");
        assertNotNull(senderIdA);
        assertNotNull(senderIdB);
        assertNotEquals(senderIdA, senderIdB);

        // Send exclusively to Client A
        serverTransport.send(senderIdA, "SECRET_FOR_A".getBytes(StandardCharsets.UTF_8));

        assertTrue(clientAReceivedLatch.await(5, TimeUnit.SECONDS));
        assertEquals("SECRET_FOR_A", clientAReceivedMsg.get());
        assertFalse(clientBReceivedLatch.await(300, TimeUnit.MILLISECONDS), "Client B should not receive data targeted to A");
        assertNull(clientBReceivedMsg.get());
    }

    // ==========================================
    // S1.5 - Reliability & Lifecycle Tests
    // ==========================================

    @Test
    @DisplayName("S1.5: Disconnect of one peer isolates and keeps other peers healthy")
    void testPeerDisconnectIsolation() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        TcpTransport clientA = createAndStartClient();
        TcpTransport clientB = createAndStartClient();
        TcpTransport clientC = createAndStartClient();

        clientA.connect("127.0.0.1", serverPort);
        clientB.connect("127.0.0.1", serverPort);
        clientC.connect("127.0.0.1", serverPort);

        waitForCondition(() -> serverTransport.getConnectionCount() == 3, 3000);

        // Disconnect Client B
        clientB.stop();
        waitForCondition(() -> serverTransport.getConnectionCount() == 2, 3000);
        assertEquals(2, serverTransport.getConnectionCount(), "Registry must reflect removal of B");

        // Verify A and C can still communicate seamlessly
        CountDownLatch remainLatch = new CountDownLatch(2);
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        serverTransport.setListener((senderId, payload) -> {
            messages.add(new String(payload, StandardCharsets.UTF_8));
            remainLatch.countDown();
        });

        clientA.send("server", "STILL_ALIVE_A".getBytes(StandardCharsets.UTF_8));
        clientC.send("server", "STILL_ALIVE_C".getBytes(StandardCharsets.UTF_8));

        assertTrue(remainLatch.await(5, TimeUnit.SECONDS));
        assertTrue(messages.contains("STILL_ALIVE_A"));
        assertTrue(messages.contains("STILL_ALIVE_C"));
    }

    @Test
    @DisplayName("S1.5: Send to unknown destination throws PravahException when multiple peers exist")
    void testSendToUnknownDestinationThrows() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        TcpTransport clientA = createAndStartClient();
        TcpTransport clientB = createAndStartClient();

        clientA.connect("127.0.0.1", serverPort);
        clientB.connect("127.0.0.1", serverPort);
        waitForCondition(() -> serverTransport.getConnectionCount() == 2, 3000);

        assertThrows(PravahException.class, () ->
                serverTransport.send("non-existent-peer-address", "DATA".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("S1.5: Server shutdown terminates all active client connections and clears registry")
    void testServerShutdownClosesAllConnections() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        TcpTransport clientA = createAndStartClient();
        TcpTransport clientB = createAndStartClient();

        clientA.connect("127.0.0.1", serverPort);
        clientB.connect("127.0.0.1", serverPort);
        waitForCondition(() -> serverTransport.getConnectionCount() == 2, 3000);

        serverTransport.stop();
        assertFalse(serverTransport.isRunning());
        assertEquals(0, serverTransport.getConnectionCount());

        // Verify clients observe disconnect
        waitForCondition(() -> !clientA.isConnected() && !clientB.isConnected(), 3000);
        assertFalse(clientA.isConnected());
        assertFalse(clientB.isConnected());
    }

    @Test
    @DisplayName("S1.5: Repeated start and stop cycles behave predictably without resource leaks")
    void testRepeatedLifecycleCycles() throws Exception {
        serverTransport = new TcpTransport(0);

        for (int i = 0; i < 3; i++) {
            serverTransport.start();
            assertTrue(serverTransport.isRunning());
            int port = serverTransport.getBoundPort();
            assertTrue(port > 0);

            TcpTransport client = createAndStartClient();
            client.connect("127.0.0.1", port);
            waitForCondition(() -> serverTransport.isConnected(), 2000);
            assertTrue(serverTransport.isConnected());

            client.stop();
            serverTransport.stop();
            assertFalse(serverTransport.isRunning());
            assertEquals(0, serverTransport.getConnectionCount());
        }
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private TcpTransport createAndStartClient() {
        TcpTransport client = new TcpTransport(0);
        client.start();
        extraClients.add(client);
        return client;
    }

    private void waitForCondition(Condition condition, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!condition.eval() && (System.currentTimeMillis() - start < timeoutMs)) {
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean eval();
    }
}