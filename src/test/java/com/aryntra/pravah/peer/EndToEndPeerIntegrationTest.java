package com.aryntra.pravah.peer;

import com.aryntra.pravah.peer.discovery.LanPeerDiscovery;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.presence.PeerPresenceState;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.protocol.ProtocolListener;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S3.6: End-to-End Peer Integration (Full Stack)")
class EndToEndPeerIntegrationTest {

    private static class PravahPeerNode implements AutoCloseable {
        final PeerId peerId;
        final TcpTransport transport;
        final PeerRegistry registry;
        final PeerPresenceManager presenceManager;
        final PeerPresenceBridge presenceBridge;
        final PeerConnectionCoordinator coordinator;
        final PeerRouter router;
        LanPeerDiscovery discovery;

        PravahPeerNode(String name, int presenceTtlMs) {
            this.peerId = PeerId.of(name);
            this.transport = new TcpTransport(0);
            this.registry = new PeerRegistry();
            this.presenceManager = new PeerPresenceManager(presenceTtlMs);
            this.presenceBridge = new PeerPresenceBridge(registry, presenceManager);
            this.coordinator = new PeerConnectionCoordinator(transport, registry, presenceBridge);
            this.router = new PeerRouter(registry, transport);
        }

        void start(int discoveryPort) {
            transport.start();
            presenceManager.start();
            discovery = new LanPeerDiscovery(peerId, transport.getBoundPort(), discoveryPort, 50);
            discovery.registerListener(presenceBridge);
            discovery.start();
        }

        @Override
        public void close() {
            if (discovery != null && discovery.isRunning()) {
                discovery.stop();
            }
            presenceManager.stop();
            if (transport.isRunning()) {
                transport.stop();
            }
        }
    }

    private PravahPeerNode aliceNode;
    private PravahPeerNode bobNode;

    @AfterEach
    void tearDown() {
        if (aliceNode != null) aliceNode.close();
        if (bobNode != null) bobNode.close();
    }

    @Test
    @DisplayName("Complete Peer Lifecycle: Discovery -> AVAILABLE -> TCP + JOIN -> CONNECTED -> MESSAGE -> LEAVE -> AVAILABLE -> TTL Expiry -> UNAVAILABLE")
    void shouldExecuteCompletePeerLifecycleEndToEnd() throws Exception {
        int discoveryPort = 49160;
        int ttlMs = 400;

        aliceNode = new PravahPeerNode("alice", ttlMs);
        bobNode = new PravahPeerNode("bob", ttlMs);

        CountDownLatch bobDiscoveredLatch = new CountDownLatch(1);
        aliceNode.presenceManager.registerListener((pId, oldPresence, newPresence) -> {
            if (pId.equals(bobNode.peerId) && newPresence.state() == PeerPresenceState.AVAILABLE) {
                bobDiscoveredLatch.countDown();
            }
        });

        // 1. Start discovery and services
        aliceNode.start(discoveryPort);
        bobNode.start(discoveryPort);

        assertTrue(bobDiscoveredLatch.await(5, TimeUnit.SECONDS), "Alice should discover Bob on LAN as AVAILABLE");
        assertEquals(PeerPresenceState.AVAILABLE, aliceNode.presenceManager.getPresence(bobNode.peerId).state());

        // 2. Establish TCP connection from Alice to Bob
        aliceNode.transport.connect("127.0.0.1", bobNode.transport.getBoundPort());

        // 3. Handshake: Alice and Bob exchange JOIN messages
        CountDownLatch aliceJoinedAtBob = new CountDownLatch(1);
        CountDownLatch bobJoinedAtAlice = new CountDownLatch(1);

        bobNode.coordinator.setProtocolListener(new ProtocolListener() {
            @Override public void onPeerJoined(String peerId, Message msg) {
                if ("alice".equals(peerId)) aliceJoinedAtBob.countDown();
            }
            @Override public void onMessageReceived(String peerId, Message msg) {}
            @Override public void onPeerLeft(String peerId, Message msg) {}
        });

        aliceNode.coordinator.setProtocolListener(new ProtocolListener() {
            @Override public void onPeerJoined(String peerId, Message msg) {
                if ("bob".equals(peerId)) bobJoinedAtAlice.countDown();
            }
            @Override public void onMessageReceived(String peerId, Message msg) {}
            @Override public void onPeerLeft(String peerId, Message msg) {}
        });

        Message aliceJoin = new Message(MessageType.JOIN, "alice", "j-1", new byte[0]);
        String aliceOutboundConn = aliceNode.coordinator.getConnectionIdForPeer(bobNode.peerId)
                .orElseGet(() -> "127.0.0.1:" + bobNode.transport.getBoundPort());
        aliceNode.transport.send(aliceOutboundConn,
                com.aryntra.pravah.protocol.FrameEncoder.encode(com.aryntra.pravah.protocol.MessageEncoder.encode(aliceJoin)));

        assertTrue(aliceJoinedAtBob.await(5, TimeUnit.SECONDS), "Bob should receive Alice's JOIN and authenticate");

        Message bobJoin = new Message(MessageType.JOIN, "bob", "j-2", new byte[0]);
        bobNode.router.send(aliceNode.peerId, bobJoin);

        assertTrue(bobJoinedAtAlice.await(5, TimeUnit.SECONDS), "Alice should receive Bob's JOIN and authenticate");

        assertEquals(PeerPresenceState.CONNECTED, bobNode.presenceManager.getPresence(aliceNode.peerId).state());
        assertEquals(PeerPresenceState.CONNECTED, aliceNode.presenceManager.getPresence(bobNode.peerId).state());

        // 4. Bi-directional MESSAGE exchange via PeerRouter
        CountDownLatch bobReceivedMsgLatch = new CountDownLatch(1);
        CountDownLatch aliceReceivedMsgLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> bobReceived = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> aliceReceived = new CopyOnWriteArrayList<>();

        bobNode.coordinator.setProtocolListener(new ProtocolListener() {
            @Override public void onPeerJoined(String peerId, Message msg) {}
            @Override public void onPeerLeft(String peerId, Message msg) {}
            @Override public void onMessageReceived(String peerId, Message msg) {
                bobReceived.add(new String(msg.payload(), StandardCharsets.UTF_8));
                bobReceivedMsgLatch.countDown();
            }
        });

        aliceNode.coordinator.setProtocolListener(new ProtocolListener() {
            @Override public void onPeerJoined(String peerId, Message msg) {}
            @Override public void onPeerLeft(String peerId, Message msg) {}
            @Override public void onMessageReceived(String peerId, Message msg) {
                aliceReceived.add(new String(msg.payload(), StandardCharsets.UTF_8));
                aliceReceivedMsgLatch.countDown();
            }
        });

        aliceNode.router.send(aliceNode.peerId, bobNode.peerId, "msg-a2b", "Hello Bob from Alice!".getBytes(StandardCharsets.UTF_8));
        assertTrue(bobReceivedMsgLatch.await(5, TimeUnit.SECONDS), "Bob should receive Alice's routed message");
        assertEquals("Hello Bob from Alice!", bobReceived.get(0));

        bobNode.router.send(bobNode.peerId, aliceNode.peerId, "msg-b2a", "Hello Alice from Bob!".getBytes(StandardCharsets.UTF_8));
        assertTrue(aliceReceivedMsgLatch.await(5, TimeUnit.SECONDS), "Alice should receive Bob's routed message");
        assertEquals("Hello Alice from Bob!", aliceReceived.get(0));

        // 5. Alice sends LEAVE -> Bob sees Alice as AVAILABLE
        Message aliceLeave = new Message(MessageType.LEAVE, "alice", "l-1", new byte[0]);
        aliceNode.router.send(bobNode.peerId, aliceLeave);

        Thread.sleep(200);
        assertEquals(PeerPresenceState.AVAILABLE, bobNode.presenceManager.getPresence(aliceNode.peerId).state());

        // 6. Register UNAVAILABLE listener BEFORE stopping discovery to avoid race
        CountDownLatch unavailableLatch = new CountDownLatch(1);
        bobNode.presenceManager.registerListener((pId, oldPresence, newPresence) -> {
            if (pId.equals(aliceNode.peerId) && newPresence.state() == PeerPresenceState.UNAVAILABLE) {
                unavailableLatch.countDown();
            }
        });

        // Stop Alice's discovery -> TTL expires -> UNAVAILABLE
        aliceNode.discovery.stop();

        // Check if already expired (possible if stop() took long)
        if (bobNode.presenceManager.getPresence(aliceNode.peerId).state() == PeerPresenceState.UNAVAILABLE) {
            // Already transitioned, test passes
        } else {
            assertTrue(unavailableLatch.await(5, TimeUnit.SECONDS), "Bob should mark Alice UNAVAILABLE after TTL expiry");
        }
        assertEquals(PeerPresenceState.UNAVAILABLE, bobNode.presenceManager.getPresence(aliceNode.peerId).state());
    }
}