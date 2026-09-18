package com.aryntra.pravah.peer;

import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.presence.PeerPresenceState;
import com.aryntra.pravah.protocol.*;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S3.6: PeerConnectionCoordinator Unit Tests")
class PeerConnectionCoordinatorTest {

    private PeerRegistry registry;
    private PeerPresenceManager presenceManager;
    private PeerPresenceBridge presenceBridge;
    private DummyTransport transport;
    private PeerConnectionCoordinator coordinator;

    private static class DummyTransport implements Transport {
        TransportListener listener;
        boolean running = true;

        @Override public String getName() { return "dummy"; }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public boolean isRunning() { return running; }
        @Override public void send(String destinationId, byte[] payload) {}
        @Override public void setListener(TransportListener listener) { this.listener = listener; }
    }

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(5000);
        presenceBridge = new PeerPresenceBridge(registry, presenceManager);
        transport = new DummyTransport();
        coordinator = new PeerConnectionCoordinator(transport, registry, presenceBridge);
    }

    @Test
    @DisplayName("JOIN message establishes identity mapping, registry record, and CONNECTED presence")
    void shouldMapIdentityOnJoinMessage() {
        String connId = "127.0.0.1:9001";
        PeerId alice = PeerId.of("alice");

        // Simulate transport connection opened
        coordinator.onConnectionOpened(connId);

        // Simulate incoming JOIN message frame
        Message joinMsg = new Message(MessageType.JOIN, "alice", "msg-1", new byte[0]);
        byte[] frame = FrameEncoder.encode(MessageEncoder.encode(joinMsg));

        coordinator.onDataReceived(connId, frame);

        // Verify connection <-> PeerId mappings
        assertEquals(alice, coordinator.getPeerIdForConnection(connId).orElse(null));
        assertEquals(connId, coordinator.getConnectionIdForPeer(alice).orElse(null));

        // Verify registry updated
        assertTrue(registry.contains(alice));
        assertEquals(connId, registry.lookup(alice).orElseThrow().connectionId());

        // Verify presence transitioned to CONNECTED
        assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(alice).state());
        assertTrue(coordinator.getSessionManager().isPeerJoined("alice"));
    }

    @Test
    @DisplayName("MESSAGE forwarding works and invokes registered ProtocolListener")
    void shouldForwardMessageToProtocolListener() {
        String connId = "127.0.0.1:9002";
        PeerId bob = PeerId.of("bob");

        AtomicReference<Message> receivedMsg = new AtomicReference<>();
        coordinator.setProtocolListener(new ProtocolListener() {
            @Override public void onPeerJoined(String peerId, Message message) {}
            @Override public void onPeerLeft(String peerId, Message message) {}
            @Override
            public void onMessageReceived(String peerId, Message message) {
                receivedMsg.set(message);
            }
        });

        coordinator.onConnectionOpened(connId);

        // Join first
        Message join = new Message(MessageType.JOIN, "bob", "j-1", new byte[0]);
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(join)));

        // Send application message
        Message chat = new Message(MessageType.MESSAGE, "bob", "m-1", "Hello Pravah".getBytes(StandardCharsets.UTF_8));
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(chat)));

        assertNotNull(receivedMsg.get());
        assertEquals("m-1", receivedMsg.get().messageId());
        assertEquals("Hello Pravah", new String(receivedMsg.get().payload(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("LEAVE message resets identity mapping and updates presence")
    void shouldHandleLeaveMessage() {
        String connId = "127.0.0.1:9003";
        PeerId charlie = PeerId.of("charlie");

        coordinator.onConnectionOpened(connId);

        Message join = new Message(MessageType.JOIN, "charlie", "j-1", new byte[0]);
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(join)));
        assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(charlie).state());

        // Send LEAVE
        Message leave = new Message(MessageType.LEAVE, "charlie", "l-1", new byte[0]);
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(leave)));

        // Mappings cleared
        assertTrue(coordinator.getPeerIdForConnection(connId).isEmpty());
        assertTrue(coordinator.getConnectionIdForPeer(charlie).isEmpty());

        // Presence transitioned away from CONNECTED
        assertNotEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(charlie).state());
        assertFalse(coordinator.getSessionManager().isPeerJoined("charlie"));
    }

    @Test
    @DisplayName("TCP connection closed lifecycle event resets peer presence and mapping")
    void shouldHandleConnectionDrop() {
        String connId = "127.0.0.1:9004";
        PeerId dave = PeerId.of("dave");

        coordinator.onConnectionOpened(connId);

        Message join = new Message(MessageType.JOIN, "dave", "j-1", new byte[0]);
        coordinator.onDataReceived(connId, FrameEncoder.encode(MessageEncoder.encode(join)));
        assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(dave).state());

        // TCP disconnect
        coordinator.onConnectionClosed(connId);

        assertTrue(coordinator.getPeerIdForConnection(connId).isEmpty());
        assertTrue(coordinator.getConnectionIdForPeer(dave).isEmpty());
        assertNotEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(dave).state());
        assertFalse(coordinator.getSessionManager().isPeerJoined("dave"));
    }

    @Test
    @DisplayName("Safe handling of unknown connection closures and invalid data")
    void shouldHandleUnknownConnectionsSafely() {
        assertDoesNotThrow(() -> coordinator.onConnectionClosed("unknown-conn"));
        assertDoesNotThrow(() -> coordinator.onDataReceived("unknown-conn", new byte[]{0, 1, 2}));
        assertDoesNotThrow(() -> coordinator.onDataReceived(null, null));
    }
}