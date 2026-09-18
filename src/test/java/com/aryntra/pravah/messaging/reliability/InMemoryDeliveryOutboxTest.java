package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryDeliveryOutbox Tests")
class InMemoryDeliveryOutboxTest {

    private InMemoryDeliveryOutbox outbox;
    private final PeerId bob = PeerId.of("bob");
    private final PeerId charlie = PeerId.of("charlie");
    private final ConversationId convBob = new ConversationId("direct:alice:bob");
    private final ConversationId convCharlie = new ConversationId("direct:alice:charlie");

    @BeforeEach
    void setUp() {
        outbox = new InMemoryDeliveryOutbox();
    }

    @Test
    @DisplayName("enqueue and findPending")
    void enqueueAndFindPending() {
        OutboxEntry entry = OutboxEntry.pending("msg-1", bob, convBob);
        outbox.enqueue(entry);

        List<OutboxEntry> pending = outbox.findPending();
        assertEquals(1, pending.size());
        assertEquals("msg-1", pending.get(0).messageId());
        assertEquals(bob, pending.get(0).destination());
    }

    @Test
    @DisplayName("enqueue duplicate messageId throws IllegalArgumentException")
    void duplicateEnqueueThrows() {
        outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));

        assertThrows(IllegalArgumentException.class,
                () -> outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob)));
    }

    @Test
    @DisplayName("findPending orders by createdAt ascending with sequence tie-breaker")
    void findPendingOrdersChronologically() {
        Instant now = Instant.now();
        OutboxEntry e1 = new OutboxEntry("msg-1", bob, convBob, OutboxState.PENDING, now.minusMillis(200));
        OutboxEntry e2 = new OutboxEntry("msg-2", bob, convBob, OutboxState.PENDING, now.minusMillis(100));
        OutboxEntry e3 = new OutboxEntry("msg-3", bob, convBob, OutboxState.PENDING, now);

        outbox.enqueue(e3);
        outbox.enqueue(e1);
        outbox.enqueue(e2);

        List<OutboxEntry> pending = outbox.findPending();
        assertEquals(3, pending.size());
        assertEquals("msg-1", pending.get(0).messageId());
        assertEquals("msg-2", pending.get(1).messageId());
        assertEquals("msg-3", pending.get(2).messageId());
    }

    @Test
    @DisplayName("findPendingForPeer filters by destination PeerId and preserves insertion order")
    void findPendingForPeerFilters() {
        Instant now = Instant.now();
        outbox.enqueue(new OutboxEntry("msg-bob-1", bob, convBob, OutboxState.PENDING, now));
        outbox.enqueue(new OutboxEntry("msg-charlie-1", charlie, convCharlie, OutboxState.PENDING, now.plusMillis(10)));
        outbox.enqueue(new OutboxEntry("msg-bob-2", bob, convBob, OutboxState.PENDING, now.plusMillis(20)));

        List<OutboxEntry> bobPending = outbox.findPendingForPeer(bob);
        assertEquals(2, bobPending.size());
        assertEquals("msg-bob-1", bobPending.get(0).messageId());
        assertEquals("msg-bob-2", bobPending.get(1).messageId());

        List<OutboxEntry> charliePending = outbox.findPendingForPeer(charlie);
        assertEquals(1, charliePending.size());
        assertEquals("msg-charlie-1", charliePending.get(0).messageId());
    }

    @Test
    @DisplayName("markCompleted changes state and excludes from findPending")
    void markCompletedExcludesFromPending() {
        outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));
        outbox.enqueue(OutboxEntry.pending("msg-2", bob, convBob));

        outbox.markCompleted("msg-1");

        List<OutboxEntry> pending = outbox.findPending();
        assertEquals(1, pending.size());
        assertEquals("msg-2", pending.get(0).messageId());

        Optional<OutboxEntry> found = outbox.findByMessageId("msg-1");
        assertTrue(found.isPresent());
        assertEquals(OutboxState.COMPLETED, found.get().state());
    }

    @Test
    @DisplayName("remove removes entry completely")
    void removeDeletesEntry() {
        outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));
        outbox.remove("msg-1");

        assertTrue(outbox.findPending().isEmpty());
        assertTrue(outbox.findByMessageId("msg-1").isEmpty());
    }

    @Test
    @DisplayName("remove non-existent message is no-op")
    void removeNonExistentIsNoOp() {
        assertDoesNotThrow(() -> outbox.remove("non-existent"));
    }
}