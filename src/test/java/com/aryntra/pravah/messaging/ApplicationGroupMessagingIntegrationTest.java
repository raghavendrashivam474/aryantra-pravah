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

@DisplayName("S4.5 Group Messaging End-to-End Integration Test")
class ApplicationGroupMessagingIntegrationTest {

    @TempDir
    Path tempDir;

    private PeerId aliceId;
    private PeerId bobId;
    private PeerId charlieId;

    private TcpTransport aliceTransport;
    private TcpTransport bobTransport;
    private TcpTransport charlieTransport;

    private PeerRegistry aliceRegistry;
    private PeerRegistry bobRegistry;
    private PeerRegistry charlieRegistry;

    private PeerPresenceManager alicePresence;
    private PeerPresenceManager bobPresence;
    private PeerPresenceManager charliePresence;

    private PeerPresenceBridge aliceBridge;
    private PeerPresenceBridge bobBridge;
    private PeerPresenceBridge charlieBridge;

    private PeerConnectionCoordinator aliceCoordinator;
    private PeerConnectionCoordinator bobCoordinator;
    private PeerConnectionCoordinator charlieCoordinator;

    private PeerRouter aliceRouter;
    private PeerRouter bobRouter;
    private PeerRouter charlieRouter;

    private Path aliceDbPath;
    private Path bobDbPath;
    private Path charlieDbPath;

    private SqliteMessageHistoryStore aliceStore;
    private SqliteMessageHistoryStore bobStore;
    private SqliteMessageHistoryStore charlieStore;

    private DefaultApplicationMessagingService aliceMessaging;
    private DefaultApplicationMessagingService bobMessaging;
    private DefaultApplicationMessagingService charlieMessaging;

    @BeforeEach
    void setUp() throws Exception {
        aliceId = PeerId.of("alice");
        bobId = PeerId.of("bob");
        charlieId = PeerId.of("charlie");

        aliceDbPath = tempDir.resolve("alice_group_history.db");
        bobDbPath = tempDir.resolve("bob_group_history.db");
        charlieDbPath = tempDir.resolve("charlie_group_history.db");

        aliceStore = new SqliteMessageHistoryStore(aliceDbPath);
        bobStore = new SqliteMessageHistoryStore(bobDbPath);
        charlieStore = new SqliteMessageHistoryStore(charlieDbPath);

        aliceTransport = new TcpTransport(0);
        bobTransport = new TcpTransport(0);
        charlieTransport = new TcpTransport(0);

        aliceTransport.start();
        bobTransport.start();
        charlieTransport.start();

        aliceRegistry = new PeerRegistry();
        bobRegistry = new PeerRegistry();
        charlieRegistry = new PeerRegistry();

        alicePresence = new PeerPresenceManager(2000);
        bobPresence = new PeerPresenceManager(2000);
        charliePresence = new PeerPresenceManager(2000);

        alicePresence.start();
        bobPresence.start();
        charliePresence.start();

        aliceBridge = new PeerPresenceBridge(aliceRegistry, alicePresence);
        bobBridge = new PeerPresenceBridge(bobRegistry, bobPresence);
        charlieBridge = new PeerPresenceBridge(charlieRegistry, charliePresence);

        aliceCoordinator = new PeerConnectionCoordinator(aliceTransport, aliceRegistry, aliceBridge);
        bobCoordinator = new PeerConnectionCoordinator(bobTransport, bobRegistry, bobBridge);
        charlieCoordinator = new PeerConnectionCoordinator(charlieTransport, charlieRegistry, charlieBridge);

        aliceRouter = new PeerRouter(aliceRegistry, aliceTransport);
        bobRouter = new PeerRouter(bobRegistry, bobTransport);
        charlieRouter = new PeerRouter(charlieRegistry, charlieTransport);

        aliceMessaging = new DefaultApplicationMessagingService(aliceId, aliceRouter, aliceCoordinator, aliceStore);
        bobMessaging = new DefaultApplicationMessagingService(bobId, bobRouter, bobCoordinator, bobStore);
        charlieMessaging = new DefaultApplicationMessagingService(charlieId, charlieRouter, charlieCoordinator, charlieStore);

        // --- Mesh Connection Setup ---

        // 1. Alice connects to Bob
        aliceTransport.connect("127.0.0.1", bobTransport.getBoundPort());
        String aliceToBobConn = "127.0.0.1:" + bobTransport.getBoundPort();
        aliceRegistry.register(bobId, aliceToBobConn);

        Message aliceJoinBob = new Message(MessageType.JOIN, aliceId.value(), "ajb-1", new byte[0]);
        aliceTransport.send(aliceToBobConn, FrameEncoder.encode(MessageEncoder.encode(aliceJoinBob)));
        Thread.sleep(100);

        Message bobJoinAlice = new Message(MessageType.JOIN, bobId.value(), "bja-1", new byte[0]);
        bobRouter.send(aliceId, bobJoinAlice);
        Thread.sleep(100);

        // 2. Bob connects to Charlie
        bobTransport.connect("127.0.0.1", charlieTransport.getBoundPort());
        String bobToCharlieConn = "127.0.0.1:" + charlieTransport.getBoundPort();
        bobRegistry.register(charlieId, bobToCharlieConn);

        Message bobJoinCharlie = new Message(MessageType.JOIN, bobId.value(), "bjc-1", new byte[0]);
        bobTransport.send(bobToCharlieConn, FrameEncoder.encode(MessageEncoder.encode(bobJoinCharlie)));
        Thread.sleep(100);

        Message charlieJoinBob = new Message(MessageType.JOIN, charlieId.value(), "cjb-1", new byte[0]);
        charlieRouter.send(bobId, charlieJoinBob);
        Thread.sleep(100);

        // 3. Alice connects to Charlie (Completing the triangle)
        aliceTransport.connect("127.0.0.1", charlieTransport.getBoundPort());
        String aliceToCharlieConn = "127.0.0.1:" + charlieTransport.getBoundPort();
        aliceRegistry.register(charlieId, aliceToCharlieConn);

        Message aliceJoinCharlie = new Message(MessageType.JOIN, aliceId.value(), "ajc-1", new byte[0]);
        aliceTransport.send(aliceToCharlieConn, FrameEncoder.encode(MessageEncoder.encode(aliceJoinCharlie)));
        Thread.sleep(100);

        Message charlieJoinAlice = new Message(MessageType.JOIN, charlieId.value(), "cja-1", new byte[0]);
        charlieRouter.send(aliceId, charlieJoinAlice);
        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (alicePresence != null) alicePresence.stop();
        if (bobPresence != null) bobPresence.stop();
        if (charliePresence != null) charliePresence.stop();

        if (aliceTransport != null) aliceTransport.stop();
        if (bobTransport != null) bobTransport.stop();
        if (charlieTransport != null) charlieTransport.stop();

        if (aliceStore != null) aliceStore.close();
        if (bobStore != null) bobStore.close();
        if (charlieStore != null) charlieStore.close();
    }

    @Test
    @DisplayName("Alice sends message to Group[Alice, Bob, Charlie] -> delivery fan-out and multi-node durability")
    void testGroupMessagingE2E() throws Exception {
        // 1. Create a Group Conversation managed by Alice's Service
        GroupConversation group = aliceMessaging.getConversationManager().createGroup("Pravah Devs", Set.of(bobId, charlieId));
        ConversationId groupId = group.conversationId();

        // 2. Pre-register the same group on Bob & Charlie to match membership records
        bobMessaging.getConversationManager().registerGroupConversation(
                new GroupConversation(groupId, "Pravah Devs", Set.of(aliceId, bobId, charlieId))
        );
        charlieMessaging.getConversationManager().registerGroupConversation(
                new GroupConversation(groupId, "Pravah Devs", Set.of(aliceId, bobId, charlieId))
        );

        // 3. Setup Listeners
        CountDownLatch bobReceiveLatch = new CountDownLatch(1);
        CountDownLatch charlieReceiveLatch = new CountDownLatch(1);
        AtomicReference<ApplicationMessage> bobMsgRef = new AtomicReference<>();
        AtomicReference<ApplicationMessage> charlieMsgRef = new AtomicReference<>();

        bobMessaging.addListener(msg -> {
            bobMsgRef.set(msg);
            bobReceiveLatch.countDown();
        });

        charlieMessaging.addListener(msg -> {
            charlieMsgRef.set(msg);
            charlieReceiveLatch.countDown();
        });

        CountDownLatch aliceSentLatch = new CountDownLatch(1);
        aliceMessaging.addLifecycleListener((msgId, state) -> {
            if (state == MessageState.SENT) {
                aliceSentLatch.countDown();
            }
        });

        // 4. Alice dispatches Group Message
        ApplicationMessage sentMsg = ApplicationMessage.text(aliceId, "Hello Group Chat!", groupId);
        aliceMessaging.send(bobId, sentMsg); // Destination parameter is ignored on group path, targets derived from ConversationManager

        // 5. Verify local state and E2E delivery Latch triggers
        assertTrue(aliceSentLatch.await(5, TimeUnit.SECONDS), "Alice did not transition message to SENT");
        assertTrue(bobReceiveLatch.await(5, TimeUnit.SECONDS), "Bob did not receive group message");
        assertTrue(charlieReceiveLatch.await(5, TimeUnit.SECONDS), "Charlie did not receive group message");

        // 6. Verify Bob's received message metadata & persistence
        ApplicationMessage bobMsg = bobMsgRef.get();
        assertEquals(sentMsg.messageId(), bobMsg.messageId());
        assertEquals("Hello Group Chat!", bobMsg.content());
        assertEquals(groupId, bobMsg.conversationId());
        assertEquals(aliceId, bobMsg.sender());

        List<ApplicationMessage> bobHistory = bobStore.getConversationHistory(groupId);
        assertEquals(1, bobHistory.size());
        assertEquals("Hello Group Chat!", bobHistory.get(0).content());
        assertEquals(MessageState.DELIVERED, bobStore.findState(sentMsg.messageId()).orElseThrow());

        // 7. Verify Charlie's received message metadata & persistence
        ApplicationMessage charlieMsg = charlieMsgRef.get();
        assertEquals(sentMsg.messageId(), charlieMsg.messageId());
        assertEquals("Hello Group Chat!", charlieMsg.content());
        assertEquals(groupId, charlieMsg.conversationId());
        assertEquals(aliceId, charlieMsg.sender());

        List<ApplicationMessage> charlieHistory = charlieStore.getConversationHistory(groupId);
        assertEquals(1, charlieHistory.size());
        assertEquals("Hello Group Chat!", charlieHistory.get(0).content());
        assertEquals(MessageState.DELIVERED, charlieStore.findState(sentMsg.messageId()).orElseThrow());

        // 8. Close and Reopen stores to prove Group history survives process restarts
        bobStore.close();
        charlieStore.close();

        try (SqliteMessageHistoryStore bobDurableStore = new SqliteMessageHistoryStore(bobDbPath);
             SqliteMessageHistoryStore charlieDurableStore = new SqliteMessageHistoryStore(charlieDbPath)) {

            List<ApplicationMessage> bHist = bobDurableStore.getConversationHistory(groupId);
            assertEquals(1, bHist.size());
            assertEquals("Hello Group Chat!", bHist.get(0).content());
            assertEquals(MessageState.DELIVERED, bobDurableStore.findState(sentMsg.messageId()).orElseThrow());

            List<ApplicationMessage> cHist = charlieDurableStore.getConversationHistory(groupId);
            assertEquals(1, cHist.size());
            assertEquals("Hello Group Chat!", cHist.get(0).content());
            assertEquals(MessageState.DELIVERED, charlieDurableStore.findState(sentMsg.messageId()).orElseThrow());
        }
    }
}