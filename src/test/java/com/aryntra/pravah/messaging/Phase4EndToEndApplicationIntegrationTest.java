package com.aryntra.pravah.messaging;

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
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.6 Grand Application End-to-End Lifecycle & Integration Test")
class Phase4EndToEndApplicationIntegrationTest {

    @TempDir
    Path tempDir;

    private static class ChatPeerNode implements AutoCloseable {
        final PeerId peerId;
        final Path dbPath;
        final SqliteMessageHistoryStore store;
        final TcpTransport transport;
        final PeerRegistry registry;
        final PeerPresenceManager presence;
        final PeerPresenceBridge bridge;
        final PeerConnectionCoordinator coordinator;
        final PeerRouter router;
        final DefaultApplicationMessagingService messaging;

        ChatPeerNode(String name, Path tempDir) {
            this.peerId = PeerId.of(name);
            this.dbPath = tempDir.resolve(name + "_chat.db");
            this.store = new SqliteMessageHistoryStore(dbPath);
            this.transport = new TcpTransport(0);
            this.registry = new PeerRegistry();
            this.presence = new PeerPresenceManager(2000);
            this.bridge = new PeerPresenceBridge(registry, presence);
            this.coordinator = new PeerConnectionCoordinator(transport, registry, bridge);
            this.router = new PeerRouter(registry, transport);
            this.messaging = new DefaultApplicationMessagingService(peerId, router, coordinator, store);
        }

        void start() {
            transport.start();
            presence.start();
        }

        void connectAndHandshake(ChatPeerNode remote) throws Exception {
            transport.connect("127.0.0.1", remote.transport.getBoundPort());
            String outboundConn = "127.0.0.1:" + remote.transport.getBoundPort();
            registry.register(remote.peerId, outboundConn);

            Message joinMsg = new Message(MessageType.JOIN, peerId.value(), "join-" + peerId.value() + "-" + remote.peerId.value(), new byte[0]);
            transport.send(outboundConn, FrameEncoder.encode(MessageEncoder.encode(joinMsg)));
            Thread.sleep(75);

            Message replyJoin = new Message(MessageType.JOIN, remote.peerId.value(), "reply-join-" + remote.peerId.value(), new byte[0]);
            remote.router.send(peerId, replyJoin);
            Thread.sleep(75);
        }

        @Override
        public void close() {
            presence.stop();
            transport.stop();
            store.close();
        }
    }

    private ChatPeerNode alice;
    private ChatPeerNode bob;
    private ChatPeerNode charlie;

    @BeforeEach
    void setUp() throws Exception {
        alice = new ChatPeerNode("alice", tempDir);
        bob = new ChatPeerNode("bob", tempDir);
        charlie = new ChatPeerNode("charlie", tempDir);

        alice.start();
        bob.start();
        charlie.start();

        // Establish fully connected 3-peer mesh
        alice.connectAndHandshake(bob);
        bob.connectAndHandshake(charlie);
        alice.connectAndHandshake(charlie);
    }

    @AfterEach
    void tearDown() {
        if (alice != null) alice.close();
        if (bob != null) bob.close();
        if (charlie != null) charlie.close();
    }

    @Test
    @DisplayName("Complete Phase 4 Acceptance: Direct Chat -> ACK -> Group Fan-Out -> Disconnect -> Reconnect -> Resume -> Grand Durability Re-open")
    void testGrandApplicationEndToEnd() throws Exception {
        // =====================================================================
        // STAGE 1: DIRECT MESSAGING & LIFECYCLE (Alice -> Bob)
        // =====================================================================
        ConversationId directConv = ConversationManager.deriveDirectConversationId(alice.peerId, bob.peerId);

        CountDownLatch bobDirectLatch = new CountDownLatch(1);
        AtomicReference<ApplicationMessage> bobDirectMsg = new AtomicReference<>();
        bob.messaging.addListener(msg -> {
            if (msg.conversationId().equals(directConv)) {
                bobDirectMsg.set(msg);
                bobDirectLatch.countDown();
            }
        });

        CountDownLatch aliceDeliveredLatch = new CountDownLatch(1);
        alice.messaging.addLifecycleListener((msgId, state) -> {
            if (state == MessageState.DELIVERED) {
                aliceDeliveredLatch.countDown();
            }
        });

        ApplicationMessage directMsg1 = alice.messaging.sendText(bob.peerId, "Stage 1: Direct Hello Bob!", directConv);
        assertEquals(MessageState.SENT, alice.messaging.getMessageState(directMsg1.messageId()));

        assertTrue(bobDirectLatch.await(5, TimeUnit.SECONDS), "Bob must receive direct message");
        assertEquals("Stage 1: Direct Hello Bob!", bobDirectMsg.get().content());
        assertEquals(alice.peerId, bobDirectMsg.get().sender());

        assertTrue(aliceDeliveredLatch.await(5, TimeUnit.SECONDS), "Alice must receive delivery ACK from Bob");
        assertEquals(MessageState.DELIVERED, alice.messaging.getMessageState(directMsg1.messageId()));

        // =====================================================================
        // STAGE 2: GROUP COMMUNICATION & FAN-OUT (Alice -> Group[Alice, Bob, Charlie])
        // =====================================================================
        GroupConversation group = alice.messaging.getConversationManager().createGroup("Core Team", Set.of(bob.peerId, charlie.peerId));
        ConversationId groupId = group.conversationId();

        bob.messaging.getConversationManager().registerGroupConversation(
                new GroupConversation(groupId, "Core Team", Set.of(alice.peerId, bob.peerId, charlie.peerId))
        );
        charlie.messaging.getConversationManager().registerGroupConversation(
                new GroupConversation(groupId, "Core Team", Set.of(alice.peerId, bob.peerId, charlie.peerId))
        );

        CountDownLatch bobGroupLatch = new CountDownLatch(1);
        CountDownLatch charlieGroupLatch = new CountDownLatch(1);
        AtomicReference<ApplicationMessage> bobGroupMsg = new AtomicReference<>();
        AtomicReference<ApplicationMessage> charlieGroupMsg = new AtomicReference<>();

        bob.messaging.addListener(msg -> {
            if (msg.conversationId().equals(groupId)) {
                bobGroupMsg.set(msg);
                bobGroupLatch.countDown();
            }
        });

        charlie.messaging.addListener(msg -> {
            if (msg.conversationId().equals(groupId)) {
                charlieGroupMsg.set(msg);
                charlieGroupLatch.countDown();
            }
        });

        ApplicationMessage groupMsg = ApplicationMessage.text(alice.peerId, "Stage 2: Group Announcement!", groupId);
        alice.messaging.send(bob.peerId, groupMsg);

        assertTrue(bobGroupLatch.await(5, TimeUnit.SECONDS), "Bob must receive group message");
        assertTrue(charlieGroupLatch.await(5, TimeUnit.SECONDS), "Charlie must receive group message");

        assertEquals(groupMsg.messageId(), bobGroupMsg.get().messageId());
        assertEquals(groupMsg.messageId(), charlieGroupMsg.get().messageId());

        // =====================================================================
        // STAGE 3: DISCONNECT & RECONNECT RESILIENCE (Resume Messaging)
        // =====================================================================
        // Simulate Alice disconnecting TCP transport from Bob
        alice.transport.stop();
        Thread.sleep(150);

        // Recreate Alice's transport on a new port (simulating network reconnect)
        ChatPeerNode aliceReconnected = new ChatPeerNode("alice", tempDir);
        aliceReconnected.start();

        // Reconnect to Bob
        aliceReconnected.connectAndHandshake(bob);

        CountDownLatch bobAfterReconnectLatch = new CountDownLatch(1);
        AtomicReference<ApplicationMessage> bobAfterReconnectMsg = new AtomicReference<>();
        bob.messaging.addListener(msg -> {
            if ("Stage 3: Post-reconnect message!".equals(msg.content())) {
                bobAfterReconnectMsg.set(msg);
                bobAfterReconnectLatch.countDown();
            }
        });

        // Alice sends message over new connection
        ApplicationMessage resumeMsg = aliceReconnected.messaging.sendText(
                bob.peerId,
                "Stage 3: Post-reconnect message!",
                directConv
        );

        assertTrue(bobAfterReconnectLatch.await(5, TimeUnit.SECONDS), "Bob must receive message after reconnect");
        assertEquals("Stage 3: Post-reconnect message!", bobAfterReconnectMsg.get().content());
        assertEquals(directConv, bobAfterReconnectMsg.get().conversationId());

        // =====================================================================
        // STAGE 4: GRAND CLOSURE & RE-OPEN DURABILITY CHECK
        // =====================================================================
        // 1. Close all active transport nodes & storage engines safely
        alice.close();
        bob.close();
        charlie.close();
        aliceReconnected.close();

        // 2. Open fresh independent SqliteMessageHistoryStore connections on the DB files
        try (SqliteMessageHistoryStore freshAliceStore = new SqliteMessageHistoryStore(alice.dbPath);
             SqliteMessageHistoryStore freshBobStore = new SqliteMessageHistoryStore(bob.dbPath);
             SqliteMessageHistoryStore freshCharlieStore = new SqliteMessageHistoryStore(charlie.dbPath)) {

            // Assert Alice direct history survived: contains BOTH pre-reconnect and post-reconnect messages
            List<ApplicationMessage> aDirectHist = freshAliceStore.getConversationHistory(directConv);
            assertEquals(2, aDirectHist.size());
            assertEquals("Stage 1: Direct Hello Bob!", aDirectHist.get(0).content());
            assertEquals("Stage 3: Post-reconnect message!", aDirectHist.get(1).content());
            assertEquals(MessageState.DELIVERED, freshAliceStore.findState(directMsg1.messageId()).orElseThrow());

            // Assert Alice group history survived
            List<ApplicationMessage> aGroupHist = freshAliceStore.getConversationHistory(groupId);
            assertEquals(1, aGroupHist.size());
            assertEquals("Stage 2: Group Announcement!", aGroupHist.get(0).content());

            // Assert Bob direct history survived, containing BOTH pre-reconnect and post-reconnect messages
            List<ApplicationMessage> bDirectHist = freshBobStore.getConversationHistory(directConv);
            assertEquals(2, bDirectHist.size());
            assertEquals("Stage 1: Direct Hello Bob!", bDirectHist.get(0).content());
            assertEquals("Stage 3: Post-reconnect message!", bDirectHist.get(1).content());
            assertEquals(MessageState.DELIVERED, freshBobStore.findState(directMsg1.messageId()).orElseThrow());
            assertEquals(MessageState.DELIVERED, freshBobStore.findState(resumeMsg.messageId()).orElseThrow());

            // Assert Bob group history survived
            List<ApplicationMessage> bGroupHist = freshBobStore.getConversationHistory(groupId);
            assertEquals(1, bGroupHist.size());
            assertEquals("Stage 2: Group Announcement!", bGroupHist.get(0).content());

            // Assert Charlie group history survived
            List<ApplicationMessage> cGroupHist = freshCharlieStore.getConversationHistory(groupId);
            assertEquals(1, cGroupHist.size());
            assertEquals("Stage 2: Group Announcement!", cGroupHist.get(0).content());
            assertEquals(MessageState.DELIVERED, freshCharlieStore.findState(groupMsg.messageId()).orElseThrow());
        }
    }
}