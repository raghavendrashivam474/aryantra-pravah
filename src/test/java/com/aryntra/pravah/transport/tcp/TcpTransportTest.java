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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class TcpTransportTest {

    private TcpTransport serverTransport;
    private TcpTransport clientTransport;

    @AfterEach
    void tearDown() {
        if (serverTransport != null) {
            serverTransport.stop();
        }
        if (clientTransport != null) {
            clientTransport.stop();
        }
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

        // Wait up to 2 seconds for server to accept
        long start = System.currentTimeMillis();
        while (!serverTransport.isConnected() && (System.currentTimeMillis() - start < 2000)) {
            Thread.sleep(20);
        }
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

        long start = System.currentTimeMillis();
        while (!serverTransport.isConnected() && (System.currentTimeMillis() - start < 2000)) {
            Thread.sleep(20);
        }

        for (int i = 1; i <= messageCount; i++) {
            clientTransport.send("server", ("Message #" + i).getBytes(StandardCharsets.UTF_8));
            Thread.sleep(20); // allow slight framing interval
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

        // Sending empty byte array should not throw
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

        long start = System.currentTimeMillis();
        while (!serverTransport.isConnected() && (System.currentTimeMillis() - start < 2000)) {
            Thread.sleep(20);
        }

        assertTrue(clientTransport.isConnected());

        // Stop client side
        clientTransport.stop();
        assertFalse(clientTransport.isRunning());

        // Wait for server to detect disconnection
        Thread.sleep(100);
        assertFalse(serverTransport.isConnected());

        // Stop server side cleanly
        serverTransport.stop();
        assertFalse(serverTransport.isRunning());
    }
}