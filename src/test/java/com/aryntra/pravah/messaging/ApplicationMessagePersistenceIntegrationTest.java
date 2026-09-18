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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.4 Application Message Persistence End-to-End Integration Test")
class ApplicationMessagePersistenceIntegrationTest {

    @TempDir
    Path tempDir;

    private PeerId aliceId;
    private PeerId bobId;

    private TcpTransport aliceTransport;
    private TcpTransport bobTransport;

    private PeerRegistry aliceRegistry;
    private PeerRegistry bobRegistry;

    private PeerPresenceManager alicePresence;
    private PeerPresenceManager bobPresence;

    private PeerPresenceBridge aliceBridge;
    private PeerPresenceBridge bobBridge;

    private PeerConnectionCoordinator aliceCoordinator;
    private PeerConnectionCoordinator bobCoordinator;

    private PeerRouter aliceRouter;
    private PeerRouter bobRouter;

    private Path aliceDbPath;
    private Path bobDbPath;
    private SqliteMessageHistoryStore aliceStore;
    private SqliteMessageHistoryStore bobStore;

    private DefaultApplicationMessagingService aliceMessaging;
    private DefaultApplicationMessagingService bobMessaging;

    @BeforeEach
    void setUp() throws Exception {
        aliceId = PeerId.of("alice");
        bobId = PeerId.of("bob");

        aliceDbPath = tempDir.resolve("alice_history.db");
        bobDbPath = tempDir.resolve("bob_history.db");
        aliceStore = new SqliteMessageHistoryStore(aliceDbPath);
        bobStore = new SqliteMessageHistoryStore(bobDbPath);

        aliceTransport = new TcpTransport(0);
        bobTransport = new TcpTransport(0);

        aliceTransport.start();
        bobTransport.start();

        aliceRegistry = new PeerRegistry();
        bobRegistry = new PeerRegistry();

        alicePresence = new PeerPresenceManager(2000);
        bobPresence = new PeerPresenceManager(2000);

        alicePresence.start();
        bobPresence.start();

        aliceBridge = new PeerPresenceBridge(aliceRegistry, alicePresence);
        bobBridge = new PeerPresenceBridge(bobRegistry, bobPresence);

        aliceCoordinator = new PeerConnectionCoordinator(aliceTransport, aliceRegistry, aliceBridge);
        bobCoordinator = new PeerConnectionCoordinator(bobTransport, bobRegistry, bobBridge);

        aliceRouter = new PeerRouter(aliceRegistry, aliceTransport);
        bobRouter = new PeerRouter(bobRegistry, bobTransport);

        aliceMessaging = new DefaultApplicationMessagingService(aliceId, aliceRouter, aliceCoordinator, aliceStore);
        bobMessaging = new DefaultApplicationMessagingService(bobId, bobRouter, bobCoordinator, bobStore);

        // 1. Establish TCP connection from Alice to Bob
        aliceTransport.connect("127.0.0.1", bobTransport.getBoundPort());
        String aliceOutboundConn = "127.0.0.1:" + bobTransport.getBoundPort();
        aliceRegistry.register(bobId, aliceOutboundConn);

        // 2. Alice sends JOIN protocol message so Bob authenticates Alice
        Message aliceJoin = new Message(MessageType.JOIN, aliceId.value(), "join-1", new byte[0]);
        byte[] encodedAliceJoin = FrameEncoder.encode(MessageEncoder.encode(aliceJoin));
        aliceTransport.send(aliceOutboundConn, encodedAliceJoin);

        Thread.sleep(100);

        // 3. Bob sends JOIN protocol message so Alice authenticates Bob
        Message bobJoin = new Message(MessageType.JOIN, bobId.value(), "join-2", new byte[0]);
        bobRouter.send(aliceId, bobJoin);

        Thread.sleep(100);
    }

    @AfterEach
    void tearDown() {
        if (alicePresence != null) alicePresence.stop();
        if (bobPresence != null) bobPresence.stop();
        if (aliceTransport != null) aliceTransport.stop();
        if (bobTransport != null) bobTransport.stop();
        if (aliceStore != null) aliceStore.close();
        if (bobStore != null) bobStore.close();
    }

    @Test
    @DisplayName("Send -> Persist locally -> Deliver -> Persist remotely -> Close DB -> Reopen & verify durability")
    void testEndToEndPersistenceAndDurability() throws Exception {
        ConversationId directConv = ConversationManager.deriveDirectConversationId(aliceId, bobId);

        CountDownLatch bobReceiveLatch = new CountDownLatch(1);
        CountDownLatch aliceDeliveredLatch = new CountDownLatch(1);
        AtomicReference<ApplicationMessage> bobReceivedMessage = new AtomicReference<>();

        bobMessaging.addListener(msg -> {
            bobReceivedMessage.set(msg);
            bobReceiveLatch.countDown();
        });

        aliceMessaging.addLifecycleListener((msgId, state) -> {
            if (state == MessageState.DELIVERED) {
                aliceDeliveredLatch.countDown();
            }
        });

        // 1. Alice sends text message
        ApplicationMessage sentMsg = aliceMessaging.sendText(bobId, "Persisted Hello Bob", directConv);

        // 2. Bob receives message
        assertTrue(bobReceiveLatch.await(5, TimeUnit.SECONDS), "Bob must receive message");
        assertEquals("Persisted Hello Bob", bobReceivedMessage.get().content());
        assertEquals(sentMsg.messageId(), bobReceivedMessage.get().messageId());

        // 3. Alice receives delivery ACK
        assertTrue(aliceDeliveredLatch.await(5, TimeUnit.SECONDS), "Alice must receive delivery ACK");

        // 4. Verify live SQLite contents
        List<ApplicationMessage> aliceHist = aliceStore.getConversationHistory(directConv);
        assertEquals(1, aliceHist.size());
        assertEquals("Persisted Hello Bob", aliceHist.get(0).content());
        assertEquals(MessageState.DELIVERED, aliceStore.findState(sentMsg.messageId()).orElseThrow());

        List<ApplicationMessage> bobHist = bobStore.getConversationHistory(directConv);
        assertEquals(1, bobHist.size());
        assertEquals("Persisted Hello Bob", bobHist.get(0).content());
        assertEquals(MessageState.DELIVERED, bobStore.findState(sentMsg.messageId()).orElseThrow());

        // 5. Close stores (simulate app shutdown)
        aliceStore.close();
        bobStore.close();

        // 6. Reopen from SQLite files (simulate app restart) and verify durability
        try (SqliteMessageHistoryStore restartedAliceStore = new SqliteMessageHistoryStore(aliceDbPath);
             SqliteMessageHistoryStore restartedBobStore = new SqliteMessageHistoryStore(bobDbPath)) {

            List<ApplicationMessage> restoredAliceHist = restartedAliceStore.getConversationHistory(directConv);
            assertEquals(1, restoredAliceHist.size());
            assertEquals(sentMsg.messageId(), restoredAliceHist.get(0).messageId());
            assertEquals("Persisted Hello Bob", restoredAliceHist.get(0).content());
            assertEquals(aliceId, restoredAliceHist.get(0).sender());
            assertEquals(MessageState.DELIVERED, restartedAliceStore.findState(sentMsg.messageId()).orElseThrow());

            List<ApplicationMessage> restoredBobHist = restartedBobStore.getConversationHistory(directConv);
            assertEquals(1, restoredBobHist.size());
            assertEquals(sentMsg.messageId(), restoredBobHist.get(0).messageId());
            assertEquals("Persisted Hello Bob", restoredBobHist.get(0).content());
            assertEquals(aliceId, restoredBobHist.get(0).sender());
            assertEquals(MessageState.DELIVERED, restartedBobStore.findState(sentMsg.messageId()).orElseThrow());
        }
    }
}