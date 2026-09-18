package com.aryntra.pravah.transport.tcp;

import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S3.6: TcpTransport Lifecycle Callbacks")
class TcpTransportLifecycleTest {

    private TcpTransport server;
    private TcpTransport client;

    @BeforeEach
    void setUp() {
        server = new TcpTransport(0);
        client = new TcpTransport(0);
    }

    @AfterEach
    void tearDown() {
        if (client != null && client.isRunning()) {
            client.stop();
        }
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should notify listener on connection open and close on both sides")
    void shouldNotifyConnectionOpenedAndClosed() throws InterruptedException {
        CountDownLatch serverOpenLatch = new CountDownLatch(1);
        CountDownLatch clientOpenLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);

        CopyOnWriteArrayList<String> serverEvents = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> clientEvents = new CopyOnWriteArrayList<>();

        server.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {}

            @Override
            public void onConnectionOpened(String connectionId) {
                serverEvents.add("OPEN:" + connectionId);
                serverOpenLatch.countDown();
            }

            @Override
            public void onConnectionClosed(String connectionId) {
                serverEvents.add("CLOSE:" + connectionId);
                serverCloseLatch.countDown();
            }
        });

        client.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {}

            @Override
            public void onConnectionOpened(String connectionId) {
                clientEvents.add("OPEN:" + connectionId);
                clientOpenLatch.countDown();
            }

            @Override
            public void onConnectionClosed(String connectionId) {
                clientEvents.add("CLOSE:" + connectionId);
                clientCloseLatch.countDown();
            }
        });

        server.start();
        client.start();

        // 1. Establish connection
        client.connect("127.0.0.1", server.getBoundPort());

        assertTrue(serverOpenLatch.await(5, TimeUnit.SECONDS), "Server should receive onConnectionOpened");
        assertTrue(clientOpenLatch.await(5, TimeUnit.SECONDS), "Client should receive onConnectionOpened");
        assertEquals(1, serverEvents.size());
        assertEquals(1, clientEvents.size());

        // 2. Disconnect client
        client.stop();

        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS), "Server should receive onConnectionClosed");
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS), "Client should receive onConnectionClosed");
    }
}