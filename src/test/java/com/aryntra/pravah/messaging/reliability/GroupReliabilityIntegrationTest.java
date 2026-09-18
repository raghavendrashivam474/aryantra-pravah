package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ApplicationMessage;
import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.messaging.InMemoryMessageHistoryStore;
import com.aryntra.pravah.messaging.MessageHistoryStore;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S5.6 Reliable Group Delivery Integration Tests")
class GroupReliabilityIntegrationTest {

    @TempDir
    Path tempDir;

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");
    private final PeerId charlie = PeerId.of("charlie");
    private final ConversationId groupId = new ConversationId("group:pravah-core");

    private MessageHistoryStore historyStore;
    private TrackingTransport transport;
    private PeerRegistry registry;
    private PeerRouter router;

    private static class TrackingTransport implements Transport {
        final Map<String, List<byte[]>> sentMessagesByDest = new ConcurrentHashMap<>();
        final Set<String> unreachableDests = ConcurrentHashMap.newKeySet();

        @Override public String getName() { return "tracking-transport"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }

        @Override
        public void send(String destinationId, byte[] payload) {
            if (unreachableDests.contains(destinationId)) {
                throw new RuntimeException("Host unreachable: " + destinationId);
            }
            sentMessagesByDest.computeIfAbsent(destinationId, k -> new ArrayList<>()).add(payload);
        }

        @Override public void setListener(TransportListener listener) {}
    }

    @BeforeEach
    void setUp() {
        historyStore = new InMemoryMessageHistoryStore();
        transport = new TrackingTransport();
        registry = new PeerRegistry();
        registry.register(bob, "conn-bob");
        registry.register(charlie, "conn-charlie");
        router = new PeerRouter(registry, transport);
    }

    @Test
    @DisplayName("Partial Delivery & Reconnect: Only offline recipient is retried; completed recipient is NOT resent")
    void testOnlyPendingRecipientIsRetriedOnReconnect() {
        GroupDeliveryOutbox groupOutbox = new InMemoryGroupDeliveryOutbox();
        DeliveryOutbox directOutbox = new InMemoryDeliveryOutbox();

        // 1. Setup group message in history
        ApplicationMessage groupMsg = ApplicationMessage.fromPayload("grp-101", alice, "Team Update".getBytes(StandardCharsets.UTF_8), groupId);
        historyStore.save(groupMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        // 2. Enqueue intents: Bob and Charlie
        groupOutbox.enqueueGroupMessage("grp-101", groupId, Set.of(bob, charlie));

        // 3. Mark Bob completed (Bob was online and ACKed)
        groupOutbox.markRecipientCompleted("grp-101", bob);

        // Charlie remains PENDING
        assertEquals(OutboxState.COMPLETED, groupOutbox.getRecipientState("grp-101", bob));
        assertEquals(OutboxState.PENDING, groupOutbox.getRecipientState("grp-101", charlie));

        // 4. Trigger DeliveryRetryManager for Charlie reconnection
        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                alice, directOutbox, groupOutbox, historyStore, router, (id, s) -> {}, 3
        );

        int dispatched = retryManager.retryPendingForPeer(charlie);
        assertEquals(1, dispatched, "Should dispatch retry message to Charlie");

        // Verify transport sent to Charlie only
        assertTrue(transport.sentMessagesByDest.containsKey("conn-charlie"));
        assertFalse(transport.sentMessagesByDest.containsKey("conn-bob"), "Bob must NOT be resent any messages");

        // Verify Charlie attempt count is 1
        assertEquals(1, groupOutbox.getAttemptCount("grp-101", charlie));
    }

    @Test
    @DisplayName("Group Bounded Retry: Participant failing max attempts transitions to ABANDONED")
    void testGroupParticipantExhaustsAttemptsAndBecomesAbandoned() {
        GroupDeliveryOutbox groupOutbox = new InMemoryGroupDeliveryOutbox();
        DeliveryOutbox directOutbox = new InMemoryDeliveryOutbox();

        ApplicationMessage groupMsg = ApplicationMessage.fromPayload("grp-abandon", alice, "Alert".getBytes(StandardCharsets.UTF_8), groupId);
        historyStore.save(groupMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        groupOutbox.enqueueGroupMessage("grp-abandon", groupId, Set.of(bob));

        // Simulate Charlie permanently unreachable
        transport.unreachableDests.add("conn-bob");

        List<RetryAttemptEvent> events = new ArrayList<>();
        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                alice, directOutbox, groupOutbox, historyStore, router, (id, s) -> {}, 2
        );
        retryManager.addRetryAttemptListener(events::add);

        // Attempt 1: failure
        retryManager.retryPendingForPeer(bob);
        assertEquals(OutboxState.PENDING, groupOutbox.getRecipientState("grp-abandon", bob));
        assertEquals(1, groupOutbox.getAttemptCount("grp-abandon", bob));

        // Attempt 2: threshold hit -> ABANDONED
        retryManager.retryPendingForPeer(bob);
        assertEquals(OutboxState.ABANDONED, groupOutbox.getRecipientState("grp-abandon", bob));
        assertEquals(2, groupOutbox.getAttemptCount("grp-abandon", bob));

        // Attempt 3: No more retry sweeps for Bob
        int dispatched = retryManager.retryPendingForPeer(bob);
        assertEquals(0, dispatched);
    }
}