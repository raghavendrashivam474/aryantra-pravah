package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ApplicationMessage;
import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.messaging.DefaultApplicationMessagingService;
import com.aryntra.pravah.messaging.MessageState;
import com.aryntra.pravah.messaging.SqliteMessageHistoryStore;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S5.3 Reconnection Delivery Retry Integration Tests")
class ReconnectAndRetryIntegrationTest {

    @TempDir
    Path tempDir;

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @Test
    @DisplayName("Message queued while peer is offline is automatically delivered and ACKed when peer reconnects")
    void queuedMessageDeliveredOnReconnection() throws Exception {
        Path aliceHistoryDb = tempDir.resolve("alice-hist.db");
        Path aliceOutboxDb = tempDir.resolve("alice-outbox.db");
        Path bobHistoryDb = tempDir.resolve("bob-hist.db");

        TcpTransport aliceTransport = new TcpTransport(0);
        TcpTransport bobTransport = new TcpTransport(0);

        PeerPresenceManager alicePresence = new PeerPresenceManager(2000);
        PeerPresenceManager bobPresence = new PeerPresenceManager(2000);

        try (SqliteMessageHistoryStore aliceHistory = new SqliteMessageHistoryStore(aliceHistoryDb);
             SqliteDeliveryOutbox aliceOutbox = new SqliteDeliveryOutbox(aliceOutboxDb);
             SqliteMessageHistoryStore bobHistory = new SqliteMessageHistoryStore(bobHistoryDb)) {

            // 1. Setup Alice node
            aliceTransport.start();
            alicePresence.start();

            PeerRegistry aliceRegistry = new PeerRegistry();
            PeerRouter aliceRouter = new PeerRouter(aliceRegistry, aliceTransport);
            PeerPresenceBridge aliceBridge = new PeerPresenceBridge(aliceRegistry, alicePresence);
            PeerConnectionCoordinator aliceCoord = new PeerConnectionCoordinator(aliceTransport, aliceRegistry, aliceBridge);

            DefaultApplicationMessagingService aliceService = new DefaultApplicationMessagingService(
                    alice, aliceRouter, aliceCoord, aliceHistory, aliceOutbox
            );

            // 2. Alice sends message while Bob is offline
            ConversationId convId = new ConversationId("direct:alice:bob");
            ApplicationMessage msg1 = ApplicationMessage.text(alice, "Offline greeting to Bob", convId);
            aliceService.send(bob, msg1);

            // Verify Alice state: message is FAILED locally, but PENDING in outbox
            assertEquals(MessageState.FAILED, aliceService.getMessageState(msg1.messageId()));
            List<OutboxEntry> pendingBefore = aliceOutbox.findPendingForPeer(bob);
            assertEquals(1, pendingBefore.size());
            assertEquals(msg1.messageId(), pendingBefore.get(0).messageId());

            // 3. Bob now starts up
            bobTransport.start();
            bobPresence.start();

            PeerRegistry bobRegistry = new PeerRegistry();
            PeerRouter bobRouter = new PeerRouter(bobRegistry, bobTransport);
            PeerPresenceBridge bobBridge = new PeerPresenceBridge(bobRegistry, bobPresence);
            PeerConnectionCoordinator bobCoord = new PeerConnectionCoordinator(bobTransport, bobRegistry, bobBridge);

            DefaultApplicationMessagingService bobService = new DefaultApplicationMessagingService(
                    bob, bobRouter, bobCoord, bobHistory
            );

            // Setup tracking for Bob receiving M1 and Alice receiving ACK
            CountDownLatch bobReceivedLatch = new CountDownLatch(1);
            AtomicReference<ApplicationMessage> bobReceivedMessage = new AtomicReference<>();
            bobService.addListener(msg -> {
                bobReceivedMessage.set(msg);
                bobReceivedLatch.countDown();
            });

            CountDownLatch aliceDeliveredLatch = new CountDownLatch(1);
            aliceService.addLifecycleListener((messageId, state) -> {
                if (messageId.equals(msg1.messageId()) && state == MessageState.DELIVERED) {
                    aliceDeliveredLatch.countDown();
                }
            });

            // 4. Connect Alice -> Bob TCP
            aliceTransport.connect("127.0.0.1", bobTransport.getBoundPort());
            String aliceOutboundConn = "127.0.0.1:" + bobTransport.getBoundPort();
            aliceRegistry.register(bob, aliceOutboundConn);

            // Alice sends JOIN so Bob authenticates Alice
            Message aliceJoin = new Message(MessageType.JOIN, alice.value(), "alice-join-1", new byte[0]);
            aliceTransport.send(aliceOutboundConn, FrameEncoder.encode(MessageEncoder.encode(aliceJoin)));

            Thread.sleep(100);

            // Bob sends JOIN to Alice -> triggers Alice's onPeerJoined -> triggers automatic retry!
            Message bobJoin = new Message(MessageType.JOIN, bob.value(), "bob-join-1", new byte[0]);
            bobRouter.send(alice, bobJoin);

            // 5. Assertions
            // Bob should receive the retried message
            assertTrue(bobReceivedLatch.await(5, TimeUnit.SECONDS), "Bob should receive retried message upon connecting");
            ApplicationMessage received = bobReceivedMessage.get();
            assertNotNull(received);
            assertEquals(msg1.messageId(), received.messageId(), "Message ID must remain unchanged across retries");
            assertEquals("Offline greeting to Bob", received.content());
            assertEquals(alice, received.sender());

            // Alice should receive Bob's ACK and transition message to DELIVERED
            assertTrue(aliceDeliveredLatch.await(5, TimeUnit.SECONDS), "Alice should receive ACK and reach DELIVERED state");
            assertEquals(MessageState.DELIVERED, aliceService.getMessageState(msg1.messageId()));

            // Alice's outbox should now have marked the delivery COMPLETED
            List<OutboxEntry> pendingAfter = aliceOutbox.findPendingForPeer(bob);
            assertTrue(pendingAfter.isEmpty(), "Outbox pending queue must be empty after successful delivery and ACK");

            OutboxEntry outboxRecord = aliceOutbox.findByMessageId(msg1.messageId()).orElseThrow();
            assertEquals(OutboxState.COMPLETED, outboxRecord.state());
        } finally {
            bobPresence.stop();
            alicePresence.stop();
            bobTransport.stop();
            aliceTransport.stop();
        }
    }

    @Test
    @DisplayName("Queued messages survive complete Alice node restart and deliver upon Bob reconnection")
    void queuedMessagesSurviveRestartAndDeliver() throws Exception {
        Path aliceHistoryDb = tempDir.resolve("alice-hist-restart.db");
        Path aliceOutboxDb = tempDir.resolve("alice-outbox-restart.db");
        Path bobHistoryDb = tempDir.resolve("bob-hist-restart.db");
        ConversationId convId = new ConversationId("direct:alice:bob");

        String queuedMsgId;

        // ═════════════════════════════════════════════════════════════
        // SESSION 1: Alice creates message while Bob is offline, then Alice shuts down
        // ═════════════════════════════════════════════════════════════
        TcpTransport transport1 = new TcpTransport(0);
        try (SqliteMessageHistoryStore historyStore1 = new SqliteMessageHistoryStore(aliceHistoryDb);
             SqliteDeliveryOutbox outbox1 = new SqliteDeliveryOutbox(aliceOutboxDb)) {

            PeerRegistry registry1 = new PeerRegistry();
            PeerRouter router1 = new PeerRouter(registry1, transport1);
            PeerPresenceManager presenceMgr1 = new PeerPresenceManager(2000);
            PeerPresenceBridge bridge1 = new PeerPresenceBridge(registry1, presenceMgr1);
            PeerConnectionCoordinator coord1 = new PeerConnectionCoordinator(transport1, registry1, bridge1);

            DefaultApplicationMessagingService service1 = new DefaultApplicationMessagingService(
                    alice, router1, coord1, historyStore1, outbox1
            );

            ApplicationMessage msg = ApplicationMessage.text(alice, "Persisted message across restart", convId);
            queuedMsgId = msg.messageId();
            service1.send(bob, msg);

            assertEquals(1, outbox1.findPendingForPeer(bob).size());
        } finally {
            transport1.stop();
        }

        // ═════════════════════════════════════════════════════════════
        // SESSION 2: Alice restarts from disk. Bob comes online. Delivery succeeds.
        // ═════════════════════════════════════════════════════════════
        TcpTransport aliceTransport2 = new TcpTransport(0);
        TcpTransport bobTransport2 = new TcpTransport(0);
        PeerPresenceManager alicePresence2 = new PeerPresenceManager(2000);
        PeerPresenceManager bobPresence2 = new PeerPresenceManager(2000);

        try (SqliteMessageHistoryStore aliceHistory2 = new SqliteMessageHistoryStore(aliceHistoryDb);
             SqliteDeliveryOutbox aliceOutbox2 = new SqliteDeliveryOutbox(aliceOutboxDb);
             SqliteMessageHistoryStore bobHistory2 = new SqliteMessageHistoryStore(bobHistoryDb)) {

            aliceTransport2.start();
            alicePresence2.start();

            PeerRegistry aliceRegistry2 = new PeerRegistry();
            PeerRouter aliceRouter2 = new PeerRouter(aliceRegistry2, aliceTransport2);
            PeerPresenceBridge aliceBridge2 = new PeerPresenceBridge(aliceRegistry2, alicePresence2);
            PeerConnectionCoordinator aliceCoord2 = new PeerConnectionCoordinator(aliceTransport2, aliceRegistry2, aliceBridge2);

            DefaultApplicationMessagingService aliceService2 = new DefaultApplicationMessagingService(
                    alice, aliceRouter2, aliceCoord2, aliceHistory2, aliceOutbox2
            );

            // Verify queue loaded from database
            assertEquals(1, aliceOutbox2.findPendingForPeer(bob).size());

            // Bob starts up
            bobTransport2.start();
            bobPresence2.start();

            PeerRegistry bobRegistry2 = new PeerRegistry();
            PeerRouter bobRouter2 = new PeerRouter(bobRegistry2, bobTransport2);
            PeerPresenceBridge bobBridge2 = new PeerPresenceBridge(bobRegistry2, bobPresence2);
            PeerConnectionCoordinator bobCoord2 = new PeerConnectionCoordinator(bobTransport2, bobRegistry2, bobBridge2);

            DefaultApplicationMessagingService bobService2 = new DefaultApplicationMessagingService(
                    bob, bobRouter2, bobCoord2, bobHistory2
            );

            CountDownLatch bobReceivedLatch = new CountDownLatch(1);
            AtomicReference<ApplicationMessage> bobReceivedMessage = new AtomicReference<>();
            bobService2.addListener(msg -> {
                bobReceivedMessage.set(msg);
                bobReceivedLatch.countDown();
            });

            CountDownLatch aliceDeliveredLatch = new CountDownLatch(1);
            aliceService2.addLifecycleListener((messageId, state) -> {
                if (messageId.equals(queuedMsgId) && state == MessageState.DELIVERED) {
                    aliceDeliveredLatch.countDown();
                }
            });

            // Connect and handshake
            aliceTransport2.connect("127.0.0.1", bobTransport2.getBoundPort());
            String aliceOutboundConn = "127.0.0.1:" + bobTransport2.getBoundPort();
            aliceRegistry2.register(bob, aliceOutboundConn);

            Message aliceJoin = new Message(MessageType.JOIN, alice.value(), "alice-join-2", new byte[0]);
            aliceTransport2.send(aliceOutboundConn, FrameEncoder.encode(MessageEncoder.encode(aliceJoin)));

            Thread.sleep(100);

            Message bobJoin = new Message(MessageType.JOIN, bob.value(), "bob-join-2", new byte[0]);
            bobRouter2.send(alice, bobJoin);

            // Assertions
            assertTrue(bobReceivedLatch.await(5, TimeUnit.SECONDS));
            assertEquals(queuedMsgId, bobReceivedMessage.get().messageId());
            assertEquals("Persisted message across restart", bobReceivedMessage.get().content());

            assertTrue(aliceDeliveredLatch.await(5, TimeUnit.SECONDS));
            assertEquals(MessageState.DELIVERED, aliceService2.getMessageState(queuedMsgId));
            assertTrue(aliceOutbox2.findPendingForPeer(bob).isEmpty());
        } finally {
            bobPresence2.stop();
            alicePresence2.stop();
            bobTransport2.stop();
            aliceTransport2.stop();
        }
    }
}