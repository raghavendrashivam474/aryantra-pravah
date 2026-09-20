package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.FrameDecoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageParser;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.TransportListener;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test proving S6.5 Path Selection with real TCP networking:
 * Application -> PeerId -> PeerRouter -> selected ConnectivityPath -> TCP -> remote peer
 */
class PathSelectionIntegrationTest {

    private TcpTransport serverTransport;
    private TcpTransport clientTransport;
    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerRouter router;

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();

        serverTransport = new TcpTransport("127.0.0.1", 0);
        clientTransport = new TcpTransport("127.0.0.1", 0);

        serverTransport.start();
        clientTransport.start();

        router = new PeerRouter(registry, clientTransport, connectivityRegistry);
    }

    @AfterEach
    void tearDown() {
        if (clientTransport != null && clientTransport.isRunning()) {
            clientTransport.stop();
        }
        if (serverTransport != null && serverTransport.isRunning()) {
            serverTransport.stop();
        }
    }

    @Test
    @DisplayName("End-to-End: PeerRouter routes through selected active ConnectivityPath over real TCP")
    void endToEndPathSelectionOverTcp() throws Exception {
        int serverPort = serverTransport.getBoundPort();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch receivedLatch = new CountDownLatch(1);
        AtomicReference<String> clientConnIdRef = new AtomicReference<>();
        AtomicReference<Message> receivedMessageRef = new AtomicReference<>();
        FrameDecoder decoder = new FrameDecoder();

        // Listen for client connection on client transport
        clientTransport.setListener(new TransportListener() {
            @Override
            public void onConnectionOpened(String connectionId) {
                clientConnIdRef.set(connectionId);
                connectedLatch.countDown();
            }

            @Override
            public void onDataReceived(String senderId, byte[] data) {}
        });

        // Listen for incoming message on server transport
        serverTransport.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] data) {
                for (byte[] frame : decoder.feed(data)) {
                    Message msg = MessageParser.parse(frame);
                    receivedMessageRef.set(msg);
                    receivedLatch.countDown();
                }
            }
        });

        // Client initiates connection to server
        clientTransport.connect("127.0.0.1", serverPort);
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Client connection should open");
        String connectionId = clientConnIdRef.get();
        assertNotNull(connectionId);

        // Register active connectivity path for Bob in PeerConnectivityRegistry
        PathId pathId = PathId.of("path:bob:tcp:loopback:" + serverPort);
        EndpointAddress endpoint = EndpointAddress.tcp("127.0.0.1", serverPort);
        ConnectivityPath activePath = ConnectivityPath.active(pathId, bob, "tcp", endpoint, connectionId);
        connectivityRegistry.registerPath(bob, activePath);

        // Application sends message targeting logical PeerId "bob" without knowing socket details
        Message outgoing = new Message(MessageType.MESSAGE, alice.value(), "msg-tcp-1", "Pravah Path Selection Works!".getBytes(StandardCharsets.UTF_8));
        router.send(bob, outgoing);

        // Verify remote peer receives exact framed message over TCP
        boolean received = receivedLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Remote peer should receive framed message via selected TCP path");

        Message receivedMsg = receivedMessageRef.get();
        assertNotNull(receivedMsg);
        assertEquals(MessageType.MESSAGE, receivedMsg.type());
        assertEquals(alice.value(), receivedMsg.senderId());
        assertEquals("msg-tcp-1", receivedMsg.messageId());
        assertEquals("Pravah Path Selection Works!", new String(receivedMsg.payload(), StandardCharsets.UTF_8));
    }
}