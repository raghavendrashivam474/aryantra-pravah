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
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S5.2 Offline Queue Durability Integration Tests")
class OfflineQueueDurabilityIntegrationTest {

    @TempDir
    Path tempDir;

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @Test
    @DisplayName("Pending messages sent while peer is offline survive service restart")
    void offlineMessagesSurviveRestart() throws Exception {
        Path historyDb = tempDir.resolve("alice-history.db");
        Path outboxDb = tempDir.resolve("alice-outbox.db");
        ConversationId convId = new ConversationId("direct:alice:bob");

        // ═════════════════════════════════════════════════════════════
        // SESSION 1: Alice creates messages while Bob is offline
        // ═════════════════════════════════════════════════════════════
        String msg1Id;
        String msg2Id;

        TcpTransport transport1 = new TcpTransport(0);
        try (SqliteMessageHistoryStore historyStore1 = new SqliteMessageHistoryStore(historyDb);
             SqliteDeliveryOutbox outbox1 = new SqliteDeliveryOutbox(outboxDb)) {

            PeerRegistry registry1 = new PeerRegistry();
            PeerRouter router1 = new PeerRouter(registry1, transport1);
            PeerPresenceManager presenceMgr1 = new PeerPresenceManager(2000);
            PeerPresenceBridge bridge1 = new PeerPresenceBridge(registry1, presenceMgr1);
            PeerConnectionCoordinator coord1 = new PeerConnectionCoordinator(transport1, registry1, bridge1);

            DefaultApplicationMessagingService service1 = new DefaultApplicationMessagingService(
                    alice, router1, coord1, historyStore1, outbox1
            );

            // Alice queues two messages to Bob
            ApplicationMessage msg1 = ApplicationMessage.text(alice, "M1: Are you there?", convId);
            ApplicationMessage msg2 = ApplicationMessage.text(alice, "M2: Ping when online", convId);
            msg1Id = msg1.messageId();
            msg2Id = msg2.messageId();

            service1.send(bob, msg1);
            service1.send(bob, msg2);

            // Both fail routing immediately
            assertEquals(MessageState.FAILED, service1.getMessageState(msg1Id));
            assertEquals(MessageState.FAILED, service1.getMessageState(msg2Id));

            // But both are in Alice's outbox
            List<OutboxEntry> pendingSession1 = outbox1.findPendingForPeer(bob);
            assertEquals(2, pendingSession1.size());
            assertEquals(msg1Id, pendingSession1.get(0).messageId());
            assertEquals(msg2Id, pendingSession1.get(1).messageId());
        } finally {
            transport1.stop();
        }

        // ═════════════════════════════════════════════════════════════
        // SESSION 2: Alice restarts completely. Stores are reloaded.
        // ═════════════════════════════════════════════════════════════
        TcpTransport transport2 = new TcpTransport(0);
        try (SqliteMessageHistoryStore historyStore2 = new SqliteMessageHistoryStore(historyDb);
             SqliteDeliveryOutbox outbox2 = new SqliteDeliveryOutbox(outboxDb)) {

            PeerRegistry registry2 = new PeerRegistry();
            PeerRouter router2 = new PeerRouter(registry2, transport2);
            PeerPresenceManager presenceMgr2 = new PeerPresenceManager(2000);
            PeerPresenceBridge bridge2 = new PeerPresenceBridge(registry2, presenceMgr2);
            PeerConnectionCoordinator coord2 = new PeerConnectionCoordinator(transport2, registry2, bridge2);

            DefaultApplicationMessagingService service2 = new DefaultApplicationMessagingService(
                    alice, router2, coord2, historyStore2, outbox2
            );

            // PROOF: Outbox still holds the two pending deliveries in strict deterministic order!
            List<OutboxEntry> pendingSession2 = outbox2.findPendingForPeer(bob);
            assertEquals(2, pendingSession2.size(), "Pending delivery queue must survive process restart");
            assertEquals(msg1Id, pendingSession2.get(0).messageId(), "Oldest pending message must remain first");
            assertEquals(msg2Id, pendingSession2.get(1).messageId(), "Newer pending message must remain second");
            assertEquals(OutboxState.PENDING, pendingSession2.get(0).state());
            assertEquals(OutboxState.PENDING, pendingSession2.get(1).state());

            // PROOF: History store also preserved the messages
            assertTrue(historyStore2.find(msg1Id).isPresent());
            assertTrue(historyStore2.find(msg2Id).isPresent());
        } finally {
            transport2.stop();
        }
    }
}