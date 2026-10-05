package com.aryntra.pravah.messaging;

import com.aryntra.pravah.connectivity.*;
import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("B.R3 Message Delivery Semantics & Ordering Integration Tests")
class MessageDeliverySemanticsTest {

    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private StubTransport transport;
    private PeerRouter router;
    private PeerConnectionCoordinator coordinator;
    private DefaultApplicationMessagingService messagingService;
    private final PeerId localPeer = PeerId.of("alice");
    private final PeerId remotePeer = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        transport = new StubTransport();
        router = new PeerRouter(registry, transport, connectivityRegistry, PathSelectionPolicy.preferSchemes("tcp", "bluetooth"));
        PeerPresenceManager presenceManager = new PeerPresenceManager(2000);
        PeerPresenceBridge presenceBridge = new PeerPresenceBridge(registry, presenceManager);
        coordinator = new PeerConnectionCoordinator(transport, registry, presenceBridge);
        messagingService = new DefaultApplicationMessagingService(localPeer, router, coordinator);
    }

    @Test
    @DisplayName("Direct send assigns monotonic sequence numbers and sets state to SENT")
    void testSendMonotonicSequence() {
        registry.register(remotePeer, "conn-bob");

        ApplicationMessage m1 = messagingService.sendText(remotePeer, "First Message");
        ApplicationMessage m2 = messagingService.sendText(remotePeer, "Second Message");

        assertEquals(1L, m1.sequenceNumber());
        assertEquals(2L, m2.sequenceNumber());
        assertEquals(MessageState.SENT, messagingService.getMessageState(m1.messageId()));
        assertEquals(MessageState.SENT, messagingService.getMessageState(m2.messageId()));
    }

    @Test
    @DisplayName("Transition buffer capture transitions message state to BUFFERED")
    void testTransitionBufferCapturesToBufferedState() {
        // Setup peer with candidate path but no active path to trigger TransitionBuffer
        ConnectivityPath candidatePath = new ConnectivityPath(
                PathId.of("p-bt-01"),
                remotePeer,
                "bluetooth",
                EndpointAddress.of("bluetooth", "00:11:22:33:44:55", 0),
                PathState.CANDIDATE,
                null
        );
        connectivityRegistry.registerPath(remotePeer, candidatePath);

        ApplicationMessage msg = messagingService.sendText(remotePeer, "Transition message");

        assertEquals(MessageState.BUFFERED, messagingService.getMessageState(msg.messageId()));
        assertEquals(1, router.transitionBuffer().size());
    }

    @Test
    @DisplayName("Receiver parses 0x04 sequenced frames and extracts sequenceNumber correctly")
    void testReceiverParsesSequencedFrame() {
        registry.register(remotePeer, "conn-bob");
        AtomicReference<ApplicationMessage> receivedHolder = new AtomicReference<>();
        messagingService.addListener(receivedHolder::set);

        // Remote peer sends JOIN
        Message joinMsg = new Message(MessageType.JOIN, "bob", "join-1", new byte[0]);
        coordinator.onDataReceived("conn-bob", FrameEncoder.encode(MessageEncoder.encode(joinMsg)));

        // Remote peer sends APP_MSG_SEQUENCED_CHAT (0x04 + 8-byte seq + text)
        byte[] textBytes = "Sequenced text payload".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[1 + 8 + textBytes.length];
        frame[0] = 0x04;
        ByteBuffer.wrap(frame, 1, 8).putLong(1042L);
        System.arraycopy(textBytes, 0, frame, 9, textBytes.length);

        Message protocolMsg = new Message(MessageType.MESSAGE, "bob", "msg-1042", frame);
        coordinator.onDataReceived("conn-bob", FrameEncoder.encode(MessageEncoder.encode(protocolMsg)));

        assertNotNull(receivedHolder.get());
        assertEquals("msg-1042", receivedHolder.get().messageId());
        assertEquals(1042L, receivedHolder.get().sequenceNumber());
        assertEquals("Sequenced text payload", receivedHolder.get().content());
    }

    @Test
    @DisplayName("Receiver deduplication discards duplicate payload while still acknowledging")
    void testReceiverDeduplication() {
        registry.register(remotePeer, "conn-bob");
        AtomicInteger listenerInvocations = new AtomicInteger(0);
        messagingService.addListener(msg -> listenerInvocations.incrementAndGet());

        // Remote peer sends JOIN
        Message joinMsg = new Message(MessageType.JOIN, "bob", "join-1", new byte[0]);
        coordinator.onDataReceived("conn-bob", FrameEncoder.encode(MessageEncoder.encode(joinMsg)));

        // Frame message
        byte[] textBytes = "Idempotent text".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[1 + 8 + textBytes.length];
        frame[0] = 0x04;
        ByteBuffer.wrap(frame, 1, 8).putLong(100L);
        System.arraycopy(textBytes, 0, frame, 9, textBytes.length);
        Message protocolMsg = new Message(MessageType.MESSAGE, "bob", "dup-msg-id", frame);

        byte[] rawWireBytes = FrameEncoder.encode(MessageEncoder.encode(protocolMsg));

        // First delivery attempt
        coordinator.onDataReceived("conn-bob", rawWireBytes);
        assertEquals(1, listenerInvocations.get());
        assertNotNull(transport.lastSentPayload, "ACK should be sent on first delivery");

        // Duplicate delivery attempt (simulating retry or transition flush replay)
        transport.lastSentPayload = null;
        coordinator.onDataReceived("conn-bob", rawWireBytes);

        // Listener must NOT be invoked a second time
        assertEquals(1, listenerInvocations.get(), "Listener should not receive duplicate message");
        // But ACK MUST still be dispatched to clear the sender's retry queue
        assertNotNull(transport.lastSentPayload, "ACK must still be dispatched for duplicate message");
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