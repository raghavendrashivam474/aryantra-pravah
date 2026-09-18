package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.1 & S4.3 DefaultApplicationMessagingService Unit & Lifecycle Tests")
class DefaultApplicationMessagingServiceTest {

    private PeerRegistry registry;
    private StubTransport transport;
    private PeerRouter router;
    private PeerConnectionCoordinator coordinator;
    private DefaultApplicationMessagingService service;
    private final PeerId localPeerId = PeerId.of("alice");
    private final PeerId remotePeerId = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        transport = new StubTransport();
        router = new PeerRouter(registry, transport);

        PeerPresenceManager presenceManager = new PeerPresenceManager(2000);
        PeerPresenceBridge presenceBridge = new PeerPresenceBridge(registry, presenceManager);
        coordinator = new PeerConnectionCoordinator(transport, registry, presenceBridge);

        service = new DefaultApplicationMessagingService(localPeerId, router, coordinator);
    }

    @Test
    @DisplayName("send() should translate ApplicationMessage and update state to SENT")
    void testSendRoutingAndState() {
        registry.register(remotePeerId, "conn-bob");

        ApplicationMessage appMsg = ApplicationMessage.text("msg-01", localPeerId, "Hello Bob");
        service.send(remotePeerId, appMsg);

        assertNotNull(transport.lastSentDestination);
        assertEquals("conn-bob", transport.lastSentDestination);
        assertEquals(MessageState.SENT, service.getMessageState("msg-01"));
    }

    @Test
    @DisplayName("send() to unroutable peer should transition state to FAILED")
    void testSendFailureState() {
        // Peer not in registry will throw IllegalStateException in PeerRouter
        ApplicationMessage appMsg = ApplicationMessage.text("msg-fail", localPeerId, "Hello Fail");
        service.send(remotePeerId, appMsg);

        assertEquals(MessageState.FAILED, service.getMessageState("msg-fail"));
    }

    @Test
    @DisplayName("Inbound chat should notify listener and send ACK back")
    void testInboundChatAndAckReply() {
        registry.register(remotePeerId, "conn-bob");

        AtomicReference<ApplicationMessage> receivedHolder = new AtomicReference<>();
        service.addListener(receivedHolder::set);

        // 1. Peer Bob joins
        Message joinMsg = new Message(MessageType.JOIN, "bob", "join-1", new byte[0]);
        byte[] encodedJoin = FrameEncoder.encode(MessageEncoder.encode(joinMsg));
        coordinator.onDataReceived("conn-bob", encodedJoin);

        // 2. Peer Bob sends framed chat (0x01 + text)
        byte[] chatText = "Incoming text".getBytes(StandardCharsets.UTF_8);
        byte[] framedChat = new byte[1 + chatText.length];
        framedChat[0] = 0x01; // APP_MSG_CHAT
        System.arraycopy(chatText, 0, framedChat, 1, chatText.length);

        Message textMsg = new Message(
                MessageType.MESSAGE,
                "bob",
                "in-01",
                framedChat
        );
        byte[] encodedText = FrameEncoder.encode(MessageEncoder.encode(textMsg));
        coordinator.onDataReceived("conn-bob", encodedText);

        assertNotNull(receivedHolder.get(), "Listener should receive message");
        assertEquals("in-01", receivedHolder.get().messageId());
        assertEquals("Incoming text", receivedHolder.get().content());

        // Assert ACK was dispatched back to Bob
        assertNotNull(transport.lastSentPayload);
        assertEquals("conn-bob", transport.lastSentDestination);
    }

    @Test
    @DisplayName("Inbound ACK should transition outgoing message state to DELIVERED")
    void testInboundAckDeliveryTransition() {
        registry.register(remotePeerId, "conn-bob");

        // Alice sends message
        ApplicationMessage appMsg = ApplicationMessage.text("msg-ack-test", localPeerId, "Waiting for ack");
        service.send(remotePeerId, appMsg);
        assertEquals(MessageState.SENT, service.getMessageState("msg-ack-test"));

        // Bob joins
        Message joinMsg = new Message(MessageType.JOIN, "bob", "join-1", new byte[0]);
        coordinator.onDataReceived("conn-bob", FrameEncoder.encode(MessageEncoder.encode(joinMsg)));

        // Bob sends ACK (0x02 + "msg-ack-test")
        byte[] ackIdBytes = "msg-ack-test".getBytes(StandardCharsets.UTF_8);
        byte[] framedAck = new byte[1 + ackIdBytes.length];
        framedAck[0] = 0x02; // APP_MSG_ACK
        System.arraycopy(ackIdBytes, 0, framedAck, 1, ackIdBytes.length);

        Message ackMsg = new Message(
                MessageType.MESSAGE,
                "bob",
                "ack-envelope-1",
                framedAck
        );
        coordinator.onDataReceived("conn-bob", FrameEncoder.encode(MessageEncoder.encode(ackMsg)));

        assertEquals(MessageState.DELIVERED, service.getMessageState("msg-ack-test"));
    }

    private static class StubTransport implements Transport {
        String lastSentDestination;
        byte[] lastSentPayload;
        TransportListener listener;

        @Override public String getName() { return "stub"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void send(String destinationId, byte[] payload) {
            this.lastSentDestination = destinationId;
            this.lastSentPayload = payload;
        }
        @Override public void setListener(TransportListener listener) {
            this.listener = listener;
        }
    }
}