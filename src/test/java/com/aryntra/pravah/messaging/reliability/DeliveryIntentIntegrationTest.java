package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ApplicationMessage;
import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.messaging.DefaultApplicationMessagingService;
import com.aryntra.pravah.messaging.InMemoryMessageHistoryStore;
import com.aryntra.pravah.messaging.MessageState;
import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S5.1 Delivery Intent Integration Tests")
class DeliveryIntentIntegrationTest {

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @Test
    @DisplayName("When destination peer is unavailable, delivery intent survives in outbox as PENDING")
    void sendToUnavailablePeerRetainsIntentInOutbox() {
        // Alice setup - Bob is NOT registered or connected
        PeerRegistry aliceRegistry = new PeerRegistry();
        TcpTransport aliceTransport = new TcpTransport(0);
        PeerRouter aliceRouter = new PeerRouter(aliceRegistry, aliceTransport);
        PeerPresenceManager presenceManager = new PeerPresenceManager(2000);
        PeerPresenceBridge presenceBridge = new PeerPresenceBridge(aliceRegistry, presenceManager);
        PeerConnectionCoordinator aliceCoord = new PeerConnectionCoordinator(aliceTransport, aliceRegistry, presenceBridge);
        DeliveryOutbox aliceOutbox = new InMemoryDeliveryOutbox();

        DefaultApplicationMessagingService aliceService = new DefaultApplicationMessagingService(
                alice, aliceRouter, aliceCoord, new InMemoryMessageHistoryStore(), aliceOutbox
        );

        // Alice sends message to Bob (offline)
        ConversationId convId = new ConversationId("direct:alice:bob");
        ApplicationMessage msg = ApplicationMessage.text(alice, "Hello Bob when you wake up", convId);

        aliceService.send(bob, msg);

        // Logical message state is FAILED (because immediate routing failed)
        assertEquals(MessageState.FAILED, aliceService.getMessageState(msg.messageId()));

        // CRITICAL S5.1 ASSERTION: The delivery intent survives in the outbox!
        List<OutboxEntry> pending = aliceOutbox.findPending();
        assertEquals(1, pending.size(), "Delivery intent must survive immediate routing failure");

        OutboxEntry entry = pending.get(0);
        assertEquals(msg.messageId(), entry.messageId());
        assertEquals(bob, entry.destination());
        assertEquals(convId, entry.conversationId());
        assertEquals(OutboxState.PENDING, entry.state());

        // Can also query by peer
        List<OutboxEntry> bobPending = aliceOutbox.findPendingForPeer(bob);
        assertEquals(1, bobPending.size());
        assertEquals(msg.messageId(), bobPending.get(0).messageId());
    }

    @Test
    @DisplayName("Destination identity in outbox is PeerId, not connectionId or transport metadata")
    void destinationIdentityIsPeerId() {
        InMemoryDeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        OutboxEntry entry = OutboxEntry.pending("msg-100", bob, new ConversationId("direct:alice:bob"));
        outbox.enqueue(entry);

        Optional<OutboxEntry> retrieved = outbox.findByMessageId("msg-100");
        assertTrue(retrieved.isPresent());
        assertEquals(bob, retrieved.get().destination());
        assertEquals("bob", retrieved.get().destination().value());
    }
}