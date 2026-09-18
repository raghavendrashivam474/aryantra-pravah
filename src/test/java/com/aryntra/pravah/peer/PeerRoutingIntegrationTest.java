package com.aryntra.pravah.peer;

import com.aryntra.pravah.protocol.*;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S3.3 - Peer-to-Peer End-to-End Routing Integration Test")
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class PeerRoutingIntegrationTest {

    private TcpTransport serverTransport;
    private TcpTransport clientTransportB;
    private TcpTransport clientTransportC;

    @AfterEach
    void tearDown() {
        if (serverTransport != null) serverTransport.stop();
        if (clientTransportB != null) clientTransportB.stop();
        if (clientTransportC != null) clientTransportC.stop();
    }

    /**
     * Receiver helper that decodes frames and processes protocol messages.
     */
    private static class NodeReceiver implements ProtocolListener {
        final ProtocolSessionManager sessionManager = new ProtocolSessionManager(this);
        final ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();
        final List<Message> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch messageLatch;

        NodeReceiver(int expectedMessages) {
            this.messageLatch = new CountDownLatch(expectedMessages);
        }

        void attachTo(TcpTransport transport) {
            transport.setListener((connectionId, chunk) -> {
                FrameDecoder decoder = decoders.computeIfAbsent(connectionId, k -> new FrameDecoder());
                List<byte[]> frames = decoder.feed(chunk);
                for (byte[] frame : frames) {
                    Message msg = MessageParser.parse(frame);
                    sessionManager.processMessage(msg);
                }
            });
        }

        @Override public void onPeerJoined(String peerId, Message message) {}
        @Override public void onPeerLeft(String peerId, Message message) {}
        @Override
        public void onMessageReceived(String peerId, Message message) {
            receivedMessages.add(message);
            messageLatch.countDown();
        }
    }

    @Test
    @DisplayName("Node A sends to Peer B and Peer C logically without referencing sockets or connection IDs")
    void logicalPeerRoutingOverTcp() throws Exception {
        // 1. Start Server Node (Node A)
        serverTransport = new TcpTransport("127.0.0.1", 0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        PeerRegistry registryA = new PeerRegistry();
        PeerRouter routerA = new PeerRouter(registryA, serverTransport);

        // Map incoming connections dynamically to PeerIds upon receiving data
        ConcurrentHashMap<String, FrameDecoder> serverDecoders = new ConcurrentHashMap<>();
        serverTransport.setListener((connId, chunk) -> {
            FrameDecoder decoder = serverDecoders.computeIfAbsent(connId, k -> new FrameDecoder());
            List<byte[]> frames = decoder.feed(chunk);
            for (byte[] frame : frames) {
                Message msg = MessageParser.parse(frame);
                // When peer announces presence, register mapping: PeerId -> connectionId
                registryA.register(PeerId.of(msg.senderId()), connId);
            }
        });

        // 2. Start Client Node B
        clientTransportB = new TcpTransport("127.0.0.1", 0);
        NodeReceiver receiverB = new NodeReceiver(1);
        receiverB.attachTo(clientTransportB);
        clientTransportB.start();

        // 3. Start Client Node C
        clientTransportC = new TcpTransport("127.0.0.1", 0);
        NodeReceiver receiverC = new NodeReceiver(1);
        receiverC.attachTo(clientTransportC);
        clientTransportC.start();

        // 4. Clients connect to Server Node A
        clientTransportB.connect("127.0.0.1", serverPort);
        clientTransportC.connect("127.0.0.1", serverPort);

        // Pre-establish session manager state on B and C to accept messages from "node-a"
        receiverB.sessionManager.processMessage(new Message(MessageType.JOIN, "node-a", "j1", new byte[0]));
        receiverC.sessionManager.processMessage(new Message(MessageType.JOIN, "node-a", "j2", new byte[0]));

        // 5. B and C announce themselves with JOIN messages to register in Node A's registry
        String serverEndpoint = "127.0.0.1:" + serverPort;
        Message joinB = new Message(MessageType.JOIN, "peer-b", "jb-1", new byte[0]);
        clientTransportB.send(serverEndpoint, FrameEncoder.encode(MessageEncoder.encode(joinB)));

        Message joinC = new Message(MessageType.JOIN, "peer-c", "jc-1", new byte[0]);
        clientTransportC.send(serverEndpoint, FrameEncoder.encode(MessageEncoder.encode(joinC)));

        // Wait a short moment for registration on Node A
        Thread.sleep(150);

        PeerId peerB = PeerId.of("peer-b");
        PeerId peerC = PeerId.of("peer-c");

        assertTrue(registryA.contains(peerB), "Registry A must contain peer-b");
        assertTrue(registryA.contains(peerC), "Registry A must contain peer-c");

        // 6. Node A sends to Peer B via logical router
        routerA.send(peerB, new Message(MessageType.MESSAGE, "node-a", "msg-to-b", "Hello Peer B!".getBytes(StandardCharsets.UTF_8)));

        // 7. Node A sends to Peer C via logical router
        routerA.send(peerC, new Message(MessageType.MESSAGE, "node-a", "msg-to-c", "Hello Peer C!".getBytes(StandardCharsets.UTF_8)));

        // 8. Verify both messages are received
        assertTrue(receiverB.messageLatch.await(3, TimeUnit.SECONDS), "Peer B must receive its message");
        assertTrue(receiverC.messageLatch.await(3, TimeUnit.SECONDS), "Peer C must receive its message");

        assertEquals(1, receiverB.receivedMessages.size());
        assertEquals("Hello Peer B!", new String(receiverB.receivedMessages.get(0).payload(), StandardCharsets.UTF_8));

        assertEquals(1, receiverC.receivedMessages.size());
        assertEquals("Hello Peer C!", new String(receiverC.receivedMessages.get(0).payload(), StandardCharsets.UTF_8));
    }
}