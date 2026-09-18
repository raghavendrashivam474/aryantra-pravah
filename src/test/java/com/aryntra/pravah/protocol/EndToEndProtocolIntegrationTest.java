package com.aryntra.pravah.protocol;

import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S2.5 — End-to-End Protocol & Chat Acceptance Tests")
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class EndToEndProtocolIntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(EndToEndProtocolIntegrationTest.class.getName());

    private TcpTransport serverTransport;
    private TcpTransport clientTransportA;
    private TcpTransport clientTransportB;

    @AfterEach
    void tearDown() {
        if (serverTransport != null) serverTransport.stop();
        if (clientTransportA != null) clientTransportA.stop();
        if (clientTransportB != null) clientTransportB.stop();
    }

    /**
     * Helper to wire a TcpTransport with per-connection Framing and a ProtocolSessionManager.
     */
    private static class PeerProtocolStack implements ProtocolListener {
        final ProtocolSessionManager sessionManager = new ProtocolSessionManager(this);
        final ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();

        final List<String> joinedPeers = Collections.synchronizedList(new ArrayList<>());
        final List<Message> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final List<String> leftPeers = Collections.synchronizedList(new ArrayList<>());

        final CountDownLatch joinLatch;
        final CountDownLatch messageLatch;
        final CountDownLatch leaveLatch;

        PeerProtocolStack(int expectedJoins, int expectedMessages, int expectedLeaves) {
            this.joinLatch = new CountDownLatch(expectedJoins);
            this.messageLatch = new CountDownLatch(expectedMessages);
            this.leaveLatch = new CountDownLatch(expectedLeaves);
        }

        void attachTo(TcpTransport transport) {
            transport.setListener((connectionId, chunk) -> {
                FrameDecoder decoder = decoders.computeIfAbsent(connectionId, k -> new FrameDecoder());
                try {
                    List<byte[]> frames = decoder.feed(chunk);
                    for (byte[] frame : frames) {
                        try {
                            Message msg = MessageParser.parse(frame);
                            sessionManager.processMessage(msg);
                        } catch (ProtocolException ex) {
                            LOGGER.fine("Protocol error from connection " + connectionId + ": " + ex.getMessage());
                        }
                    }
                } catch (ProtocolException ex) {
                    LOGGER.fine("Framing error on connection " + connectionId + ": " + ex.getMessage());
                    decoder.reset();
                }
            });
        }

        @Override
        public void onPeerJoined(String peerId, Message message) {
            joinedPeers.add(peerId);
            joinLatch.countDown();
        }

        @Override
        public void onMessageReceived(String peerId, Message message) {
            receivedMessages.add(message);
            messageLatch.countDown();
        }

        @Override
        public void onPeerLeft(String peerId, Message message) {
            leftPeers.add(peerId);
            leaveLatch.countDown();
        }
    }

    @Test
    @DisplayName("Complete acceptance flow: JOIN -> multiple MESSAGEs -> LEAVE over real TCP")
    void shouldExecuteFullConversationOverTcp() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int port = serverTransport.getBoundPort();

        PeerProtocolStack serverStack = new PeerProtocolStack(1, 3, 1);
        serverStack.attachTo(serverTransport);

        clientTransportA = new TcpTransport(0);
        clientTransportA.start();
        clientTransportA.connect("127.0.0.1", port);

        String serverDest = "127.0.0.1:" + port;

        // 1. Peer A sends JOIN
        Message join = new Message(MessageType.JOIN, "alice", "msg-join", new byte[0]);
        clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(join)));

        assertTrue(serverStack.joinLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for JOIN");
        assertEquals(1, serverStack.joinedPeers.size());
        assertEquals("alice", serverStack.joinedPeers.get(0));

        // 2. Peer A sends 3 sequential MESSAGEs
        Message m1 = new Message(MessageType.MESSAGE, "alice", "msg-1", "Hello Pravah!".getBytes(StandardCharsets.UTF_8));
        Message m2 = new Message(MessageType.MESSAGE, "alice", "msg-2", "How are you?".getBytes(StandardCharsets.UTF_8));
        Message m3 = new Message(MessageType.MESSAGE, "alice", "msg-3", "All systems green.".getBytes(StandardCharsets.UTF_8));

        clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(m1)));
        clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(m2)));
        clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(m3)));

        assertTrue(serverStack.messageLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for 3 messages");
        assertEquals(3, serverStack.receivedMessages.size());
        assertEquals("Hello Pravah!", new String(serverStack.receivedMessages.get(0).payload(), StandardCharsets.UTF_8));
        assertEquals("How are you?", new String(serverStack.receivedMessages.get(1).payload(), StandardCharsets.UTF_8));
        assertEquals("All systems green.", new String(serverStack.receivedMessages.get(2).payload(), StandardCharsets.UTF_8));

        // 3. Peer A sends LEAVE
        Message leave = new Message(MessageType.LEAVE, "alice", "msg-leave", new byte[0]);
        clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(leave)));

        assertTrue(serverStack.leaveLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for LEAVE");
        assertEquals(1, serverStack.leftPeers.size());
        assertEquals("alice", serverStack.leftPeers.get(0));
        assertEquals(PeerState.LEFT, serverStack.sessionManager.getPeerState("alice"));
    }

    @Test
    @DisplayName("Adversarial Isolation: Corrupted/malicious peer traffic does not affect valid peers")
    void shouldIsolateValidPeersFromMaliciousPeerAttacks() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int port = serverTransport.getBoundPort();

        PeerProtocolStack serverStack = new PeerProtocolStack(2, 2, 0);
        serverStack.attachTo(serverTransport);

        // Valid Peer A connects and JOINs
        clientTransportA = new TcpTransport(0);
        clientTransportA.start();
        clientTransportA.connect("127.0.0.1", port);
        String serverDest = "127.0.0.1:" + port;

        Message joinA = new Message(MessageType.JOIN, "alice", "j-a", new byte[0]);
        clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(joinA)));

        // Valid Peer B connects and JOINs
        clientTransportB = new TcpTransport(0);
        clientTransportB.start();
        clientTransportB.connect("127.0.0.1", port);

        Message joinB = new Message(MessageType.JOIN, "bob", "j-b", new byte[0]);
        clientTransportB.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(joinB)));

        assertTrue(serverStack.joinLatch.await(5, TimeUnit.SECONDS));

        // Malicious raw socket connects and sprays invalid frames / garbage
        try (Socket attackerSocket = new Socket("127.0.0.1", port)) {
            OutputStream out = attackerSocket.getOutputStream();

            // Attack 1: Random junk bytes
            out.write(new byte[]{ (byte)0xFF, 0x00, 0x12, 0x34, 0x56, 0x78 });
            out.flush();

            // Attack 2: Oversized frame header (100 MB)
            out.write(new byte[]{ 0x06, 0x40, 0x00, 0x00 });
            out.flush();

            // Attack 3: Bad magic in valid length frame
            byte[] badMagicFrame = new byte[]{ 0x00, 0x00, 0x00, 0x0C, 'B', 'A', 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0 };
            out.write(badMagicFrame);
            out.flush();

            // Valid Peer A and Peer B send normal messages concurrently
            Message msgA = new Message(MessageType.MESSAGE, "alice", "m-a1", "Alice is safe".getBytes(StandardCharsets.UTF_8));
            Message msgB = new Message(MessageType.MESSAGE, "bob", "m-b1", "Bob is safe".getBytes(StandardCharsets.UTF_8));

            clientTransportA.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(msgA)));
            clientTransportB.send(serverDest, FrameEncoder.encode(MessageEncoder.encode(msgB)));

            // Valid messages must arrive without disruption
            assertTrue(serverStack.messageLatch.await(5, TimeUnit.SECONDS));
            assertEquals(2, serverStack.receivedMessages.size());
            assertEquals("Alice is safe", new String(serverStack.receivedMessages.get(0).payload(), StandardCharsets.UTF_8));
            assertEquals("Bob is safe", new String(serverStack.receivedMessages.get(1).payload(), StandardCharsets.UTF_8));
        }
    }
}