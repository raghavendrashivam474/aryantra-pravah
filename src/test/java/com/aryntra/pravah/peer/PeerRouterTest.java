package com.aryntra.pravah.peer;

import com.aryntra.pravah.protocol.FrameDecoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageParser;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PeerRouter - S3.3 Peer Routing Unit Tests")
class PeerRouterTest {

    private PeerRegistry registry;
    private MockTransport transport;
    private PeerRouter router;

    /**
     * Minimal mock transport to capture dispatches without real sockets.
     */
    private static class MockTransport implements Transport {
        final Map<String, List<byte[]>> sentData = new HashMap<>();
        boolean running = true;
        boolean failNext = false;

        @Override public String getName() { return "mock-transport"; }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public boolean isRunning() { return running; }
        @Override public void setListener(TransportListener listener) {}

        @Override
        public void send(String destinationId, byte[] payload) {
            if (failNext) {
                throw new RuntimeException("Simulated transport write failure");
            }
            sentData.computeIfAbsent(destinationId, k -> new ArrayList<>()).add(payload);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        transport = new MockTransport();
        router = new PeerRouter(registry, transport);
    }

    @Test
    @DisplayName("send() to registered connected peer succeeds and dispatches framed message")
    void sendToConnectedPeer() {
        PeerId destination = PeerId.of("peer-bob");
        registry.register(destination, "conn-42");

        Message msg = new Message(MessageType.MESSAGE, "peer-alice", "msg-101", "Hello Bob".getBytes(StandardCharsets.UTF_8));
        router.send(destination, msg);

        List<byte[]> sent = transport.sentData.get("conn-42");
        assertNotNull(sent);
        assertEquals(1, sent.size());

        // Verify that the sent data correctly decodes through FrameDecoder + MessageParser
        FrameDecoder decoder = new FrameDecoder();
        List<byte[]> frames = decoder.feed(sent.get(0));
        assertEquals(1, frames.size());

        Message parsed = MessageParser.parse(frames.get(0));
        assertEquals(MessageType.MESSAGE, parsed.type());
        assertEquals("peer-alice", parsed.senderId());
        assertEquals("msg-101", parsed.messageId());
        assertArrayEquals("Hello Bob".getBytes(StandardCharsets.UTF_8), parsed.payload());
    }

    @Test
    @DisplayName("send() with sender/destination convenience overload")
    void sendConvenienceOverload() {
        PeerId sender = PeerId.of("peer-alice");
        PeerId destination = PeerId.of("peer-charlie");
        registry.register(destination, "conn-99");

        router.send(sender, destination, "msg-202", "Quick payload".getBytes(StandardCharsets.UTF_8));

        List<byte[]> sent = transport.sentData.get("conn-99");
        assertNotNull(sent);
        assertEquals(1, sent.size());

        FrameDecoder decoder = new FrameDecoder();
        List<byte[]> frames = decoder.feed(sent.get(0));
        Message parsed = MessageParser.parse(frames.get(0));
        assertEquals("peer-alice", parsed.senderId());
        assertEquals("msg-202", parsed.messageId());
    }

    @Test
    @DisplayName("send() to unknown peer throws PeerRoutingException")
    void sendToUnknownPeerThrows() {
        PeerId unknown = PeerId.of("ghost-peer");
        Message msg = new Message(MessageType.MESSAGE, "alice", "m1", "data".getBytes());

        PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.send(unknown, msg));
        assertTrue(ex.getMessage().contains("not registered"));
    }

    @Test
    @DisplayName("send() to registered but disconnected peer throws PeerRoutingException")
    void sendToDisconnectedPeerThrows() {
        PeerId disconnected = PeerId.of("offline-peer");
        registry.register(PeerRecord.disconnected(disconnected));

        Message msg = new Message(MessageType.MESSAGE, "alice", "m1", "data".getBytes());
        PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.send(disconnected, msg));
        assertTrue(ex.getMessage().contains("disconnected"));
    }

    @Test
    @DisplayName("send() handles transport failure and wraps in PeerRoutingException")
    void sendTransportFailureWrapped() {
        PeerId bob = PeerId.of("bob");
        registry.register(bob, "conn-bob");
        transport.failNext = true;

        Message msg = new Message(MessageType.MESSAGE, "alice", "m1", "data".getBytes());
        PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.send(bob, msg));
        assertTrue(ex.getMessage().contains("Failed to dispatch"));
    }

    @Test
    @DisplayName("send() validates null arguments")
    void sendNullValidation() {
        PeerId id = PeerId.of("peer");
        Message msg = new Message(MessageType.MESSAGE, "alice", "m1", "data".getBytes());

        assertThrows(NullPointerException.class, () -> router.send(null, msg));
        assertThrows(NullPointerException.class, () -> router.send(id, null));
    }

    @Test
    @DisplayName("routes messages to distinct connections for different peers")
    void routeToMultiplePeers() {
        PeerId bob = PeerId.of("bob");
        PeerId charlie = PeerId.of("charlie");
        registry.register(bob, "conn-bob-10");
        registry.register(charlie, "conn-charlie-20");

        router.send(bob, new Message(MessageType.MESSAGE, "alice", "m1", "Bob Data".getBytes()));
        router.send(charlie, new Message(MessageType.MESSAGE, "alice", "m2", "Charlie Data".getBytes()));

        assertEquals(1, transport.sentData.get("conn-bob-10").size());
        assertEquals(1, transport.sentData.get("conn-charlie-20").size());
    }
}