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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class BoundedRetryIntegrationTest {

    private PeerId localPeer;
    private PeerId remotePeer;
    private ConversationId conversationId;
    private MessageHistoryStore historyStore;

    @TempDir
    Path tempDir;

    private static class FailingTransport implements Transport {
        @Override public String getName() { return "failing-transport"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void send(String destinationId, byte[] payload) {
            throw new RuntimeException("Transport send failure: destination unreachable");
        }
        @Override public void setListener(TransportListener listener) {}
    }

    private PeerRouter createFailingRouter(PeerId registeredPeer) {
        PeerRegistry registry = new PeerRegistry();
        if (registeredPeer != null) {
            registry.register(registeredPeer, "conn-" + registeredPeer.value());
        }
        return new PeerRouter(registry, new FailingTransport());
    }

    @BeforeEach
    void setUp() {
        localPeer = PeerId.of("alice");
        remotePeer = PeerId.of("bob");
        conversationId = new ConversationId("direct:alice-bob");
        historyStore = new InMemoryMessageHistoryStore();
    }

    @Test
    void testAttemptCounterStartsAtZeroAndIncrementsOnRetry() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        OutboxEntry entry = OutboxEntry.pending("msg-1", remotePeer, conversationId);
        outbox.enqueue(entry);

        assertEquals(0, outbox.findByMessageId("msg-1").get().attemptCount());

        ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-1", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
        historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        AtomicInteger attemptCallbackCount = new AtomicInteger(0);
        PeerRouter failingRouter = createFailingRouter(remotePeer);

        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                localPeer, outbox, historyStore, failingRouter,
                (msgId, success) -> {
                    assertFalse(success);
                    attemptCallbackCount.incrementAndGet();
                },
                3
        );

        retryManager.retryPendingForPeer(remotePeer);

        assertEquals(1, attemptCallbackCount.get());
        OutboxEntry updated = outbox.findByMessageId("msg-1").get();
        assertEquals(1, updated.attemptCount());
        assertEquals(OutboxState.PENDING, updated.state());
    }

    @Test
    void testMaximumAttemptsReachedTriggersAbandonedState() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        outbox.enqueue(OutboxEntry.pending("msg-limit", remotePeer, conversationId));

        ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-limit", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
        historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        PeerRouter failingRouter = createFailingRouter(remotePeer);
        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                localPeer, outbox, historyStore, failingRouter, (id, s) -> {}, 2
        );

        // First failure: updates to 1 attempt, status remains PENDING
        retryManager.retryPendingForPeer(remotePeer);
        assertEquals(OutboxState.PENDING, outbox.findByMessageId("msg-limit").get().state());
        assertEquals(1, outbox.findByMessageId("msg-limit").get().attemptCount());

        // Second failure: updates to 2 attempts, threshold hit -> transitions to ABANDONED
        retryManager.retryPendingForPeer(remotePeer);
        assertEquals(OutboxState.ABANDONED, outbox.findByMessageId("msg-limit").get().state());
        assertEquals(2, outbox.findByMessageId("msg-limit").get().attemptCount());

        // Third trigger: should be skipped from subsequent sweeps completely
        int dispatched = retryManager.retryPendingForPeer(remotePeer);
        assertEquals(0, dispatched);
    }

    @Test
    void testSqlitePersistenceSurvivesRestartsAndRetainsMetadata() throws Exception {
        Path dbPath = tempDir.resolve("outbox.db");

        // Step 1: Create outbox, enqueue entry, run one failed retry
        try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
            outbox.enqueue(OutboxEntry.pending("msg-persist", remotePeer, conversationId));

            ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-persist", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
            historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

            PeerRouter failingRouter = createFailingRouter(remotePeer);
            DeliveryRetryManager retryManager = new DeliveryRetryManager(
                    localPeer, outbox, historyStore, failingRouter, (id, s) -> {}, 3
            );

            retryManager.retryPendingForPeer(remotePeer);

            OutboxEntry entry = outbox.findByMessageId("msg-persist").get();
            assertEquals(1, entry.attemptCount());
            assertEquals(OutboxState.PENDING, entry.state());
        } // Connection closed here

        // Step 2: Reopen outbox, verify state and attempt count are preserved perfectly
        try (SqliteDeliveryOutbox reopenedOutbox = new SqliteDeliveryOutbox(dbPath)) {
            Optional<OutboxEntry> recoveredOpt = reopenedOutbox.findByMessageId("msg-persist");
            assertTrue(recoveredOpt.isPresent());

            OutboxEntry recovered = recoveredOpt.get();
            assertEquals("msg-persist", recovered.messageId());
            assertEquals(remotePeer, recovered.destination());
            assertEquals(conversationId, recovered.conversationId());
            assertEquals(1, recovered.attemptCount());
            assertEquals(OutboxState.PENDING, recovered.state());

            // Run second failure to transition to ABANDONED
            PeerRouter failingRouter = createFailingRouter(remotePeer);
            DeliveryRetryManager retryManager = new DeliveryRetryManager(
                    localPeer, reopenedOutbox, historyStore, failingRouter, (id, s) -> {}, 2
            );

            retryManager.retryPendingForPeer(remotePeer);

            OutboxEntry finalEntry = reopenedOutbox.findByMessageId("msg-persist").get();
            assertEquals(2, finalEntry.attemptCount());
            assertEquals(OutboxState.ABANDONED, finalEntry.state());
        }

        // Step 3: Reopen again, verify ABANDONED state survives process boundary
        try (SqliteDeliveryOutbox reopenedAgain = new SqliteDeliveryOutbox(dbPath)) {
            OutboxEntry restored = reopenedAgain.findByMessageId("msg-persist").get();
            assertEquals(OutboxState.ABANDONED, restored.state());
            assertEquals(2, restored.attemptCount());

            // Verify it is excluded from pending sets
            assertTrue(reopenedAgain.findPending().isEmpty());
            assertTrue(reopenedAgain.findPendingForPeer(remotePeer).isEmpty());
        }
    }

    @Test
    void testSuccessfulAckSetsCompletedEvenIfCounterIsElevated() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        outbox.enqueue(OutboxEntry.pending("msg-success", remotePeer, conversationId));

        outbox.updateAttemptCount("msg-success", 2);
        outbox.markCompleted("msg-success");

        OutboxEntry entry = outbox.findByMessageId("msg-success").get();
        assertEquals(OutboxState.COMPLETED, entry.state());
        assertEquals(2, entry.attemptCount());
        
        assertTrue(outbox.findPending().isEmpty());
    }
}