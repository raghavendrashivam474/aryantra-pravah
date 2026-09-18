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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class DeliveryAttemptObservabilityTest {

    private PeerId localPeer;
    private PeerId remotePeer;
    private ConversationId conversationId;
    private MessageHistoryStore historyStore;

    private static class CapturingTransport implements Transport {
        boolean failNext = false;
        @Override public String getName() { return "capturing"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void send(String destinationId, byte[] payload) {
            if (failNext) {
                throw new RuntimeException("Simulated socket write failure");
            }
        }
        @Override public void setListener(TransportListener listener) {}
    }

    private CapturingTransport transport;
    private PeerRouter router;

    @BeforeEach
    void setUp() {
        localPeer = PeerId.of("alice");
        remotePeer = PeerId.of("bob");
        conversationId = new ConversationId("direct:alice-bob");
        historyStore = new InMemoryMessageHistoryStore();

        PeerRegistry registry = new PeerRegistry();
        registry.register(remotePeer, "conn-bob");
        transport = new CapturingTransport();
        router = new PeerRouter(registry, transport);
    }

    @Test
    void testDispatchedEventEmittedOnSuccessfulRetry() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        outbox.enqueue(OutboxEntry.pending("msg-obs-1", remotePeer, conversationId));

        ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-obs-1", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
        historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        List<RetryAttemptEvent> events = new ArrayList<>();
        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                localPeer, outbox, historyStore, router, (id, s) -> {}, 3
        );
        retryManager.addRetryAttemptListener(events::add);

        int dispatched = retryManager.retryPendingForPeer(remotePeer);
        assertEquals(1, dispatched);

        assertEquals(1, events.size());
        RetryAttemptEvent event = events.get(0);
        assertEquals("msg-obs-1", event.messageId());
        assertEquals(remotePeer, event.destination());
        assertEquals(1, event.attemptCount());
        assertEquals(RetryAttemptEvent.Outcome.DISPATCHED, event.outcome());
        assertTrue(event.timestamp() > 0);
    }

    @Test
    void testFailedAndAbandonedEventsEmitted() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        outbox.enqueue(OutboxEntry.pending("msg-obs-fail", remotePeer, conversationId));

        ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-obs-fail", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
        historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        transport.failNext = true;

        List<RetryAttemptEvent> events = new ArrayList<>();
        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                localPeer, outbox, historyStore, router, (id, s) -> {}, 2
        );
        retryManager.addRetryAttemptListener(events::add);

        // Attempt 1: failure
        retryManager.retryPendingForPeer(remotePeer);
        assertEquals(1, events.size());
        assertEquals(RetryAttemptEvent.Outcome.FAILED, events.get(0).outcome());
        assertEquals(1, events.get(0).attemptCount());

        // Attempt 2: failure reaching max attempts -> ABANDONED event
        retryManager.retryPendingForPeer(remotePeer);
        assertEquals(2, events.size());
        assertEquals(RetryAttemptEvent.Outcome.ABANDONED, events.get(1).outcome());
        assertEquals(2, events.get(1).attemptCount());
    }

    @Test
    void testListenerExceptionDoesNotBreakDeliveryOrOtherListeners() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        outbox.enqueue(OutboxEntry.pending("msg-obs-safe", remotePeer, conversationId));

        ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-obs-safe", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
        historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        AtomicBoolean healthyListenerCalled = new AtomicBoolean(false);

        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                localPeer, outbox, historyStore, router, (id, s) -> {}, 3
        );

        // Faulty listener that throws
        retryManager.addRetryAttemptListener(event -> {
            throw new RuntimeException("Crashing UI listener");
        });

        // Healthy listener
        retryManager.addRetryAttemptListener(event -> {
            healthyListenerCalled.set(true);
        });

        // Retry must succeed without throwing exception
        assertDoesNotThrow(() -> {
            int count = retryManager.retryPendingForPeer(remotePeer);
            assertEquals(1, count);
        });

        // Healthy listener must still have been called
        assertTrue(healthyListenerCalled.get());
    }

    @Test
    void testListenerRegistrationAndRemoval() {
        DeliveryOutbox outbox = new InMemoryDeliveryOutbox();
        outbox.enqueue(OutboxEntry.pending("msg-obs-rem", remotePeer, conversationId));

        ApplicationMessage appMsg = ApplicationMessage.fromPayload("msg-obs-rem", localPeer, "hello".getBytes(StandardCharsets.UTF_8), conversationId);
        historyStore.save(appMsg, com.aryntra.pravah.messaging.MessageState.CREATED);

        List<RetryAttemptEvent> events = new ArrayList<>();
        RetryAttemptListener listener = events::add;

        DeliveryRetryManager retryManager = new DeliveryRetryManager(
                localPeer, outbox, historyStore, router, (id, s) -> {}, 3
        );

        retryManager.addRetryAttemptListener(listener);
        retryManager.removeRetryAttemptListener(listener);

        retryManager.retryPendingForPeer(remotePeer);

        // Listener removed before retry; should receive 0 events
        assertTrue(events.isEmpty());
    }
}