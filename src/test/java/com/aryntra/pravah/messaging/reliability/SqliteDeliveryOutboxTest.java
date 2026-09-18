package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SqliteDeliveryOutbox Tests")
class SqliteDeliveryOutboxTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private final PeerId bob = PeerId.of("bob");
    private final PeerId charlie = PeerId.of("charlie");
    private final ConversationId convBob = new ConversationId("direct:alice:bob");
    private final ConversationId convCharlie = new ConversationId("direct:alice:charlie");

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("outbox-test.db");
    }

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("enqueue and findPending retrieves inserted entry")
        void enqueueAndFindPending() throws Exception {
            try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
                outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));

                List<OutboxEntry> pending = outbox.findPending();
                assertEquals(1, pending.size());
                assertEquals("msg-1", pending.get(0).messageId());
                assertEquals(bob, pending.get(0).destination());
                assertEquals(convBob, pending.get(0).conversationId());
                assertEquals(OutboxState.PENDING, pending.get(0).state());
            }
        }

        @Test
        @DisplayName("Duplicate messageId throws IllegalArgumentException")
        void duplicateEnqueueThrows() throws Exception {
            try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
                outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));

                assertThrows(IllegalArgumentException.class,
                        () -> outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob)));
            }
        }

        @Test
        @DisplayName("markCompleted updates status and removes from findPending")
        void markCompleted() throws Exception {
            try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
                outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));
                outbox.enqueue(OutboxEntry.pending("msg-2", bob, convBob));

                outbox.markCompleted("msg-1");

                List<OutboxEntry> pending = outbox.findPending();
                assertEquals(1, pending.size());
                assertEquals("msg-2", pending.get(0).messageId());

                Optional<OutboxEntry> completed = outbox.findByMessageId("msg-1");
                assertTrue(completed.isPresent());
                assertEquals(OutboxState.COMPLETED, completed.get().state());
            }
        }

        @Test
        @DisplayName("remove deletes entry completely")
        void removeDeletes() throws Exception {
            try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
                outbox.enqueue(OutboxEntry.pending("msg-1", bob, convBob));
                outbox.remove("msg-1");

                assertTrue(outbox.findPending().isEmpty());
                assertTrue(outbox.findByMessageId("msg-1").isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("Ordering & Filtering")
    class OrderingAndFiltering {

        @Test
        @DisplayName("findPending orders entries deterministically by seq ASC")
        void deterministicOrdering() throws Exception {
            try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
                outbox.enqueue(OutboxEntry.pending("msg-first", bob, convBob));
                outbox.enqueue(OutboxEntry.pending("msg-second", bob, convBob));
                outbox.enqueue(OutboxEntry.pending("msg-third", bob, convBob));

                List<OutboxEntry> pending = outbox.findPending();
                assertEquals(3, pending.size());
                assertEquals("msg-first", pending.get(0).messageId());
                assertEquals("msg-second", pending.get(1).messageId());
                assertEquals("msg-third", pending.get(2).messageId());
            }
        }

        @Test
        @DisplayName("findPendingForPeer isolates queues per destination")
        void peerIsolation() throws Exception {
            try (SqliteDeliveryOutbox outbox = new SqliteDeliveryOutbox(dbPath)) {
                outbox.enqueue(OutboxEntry.pending("msg-bob-1", bob, convBob));
                outbox.enqueue(OutboxEntry.pending("msg-charlie-1", charlie, convCharlie));
                outbox.enqueue(OutboxEntry.pending("msg-bob-2", bob, convBob));

                List<OutboxEntry> bobPending = outbox.findPendingForPeer(bob);
                assertEquals(2, bobPending.size());
                assertEquals("msg-bob-1", bobPending.get(0).messageId());
                assertEquals("msg-bob-2", bobPending.get(1).messageId());

                List<OutboxEntry> charliePending = outbox.findPendingForPeer(charlie);
                assertEquals(1, charliePending.size());
                assertEquals("msg-charlie-1", charliePending.get(0).messageId());
            }
        }
    }

    @Nested
    @DisplayName("Durability & Restart")
    class DurabilityAndRestart {

        @Test
        @DisplayName("Pending messages survive outbox close and reopen")
        void pendingMessagesSurviveRestart() throws Exception {
            // 1. First session: enqueue pending work
            try (SqliteDeliveryOutbox outbox1 = new SqliteDeliveryOutbox(dbPath)) {
                outbox1.enqueue(OutboxEntry.pending("msg-durable-1", bob, convBob));
                outbox1.enqueue(OutboxEntry.pending("msg-durable-2", bob, convBob));
                outbox1.enqueue(OutboxEntry.pending("msg-completed", bob, convBob));
                outbox1.markCompleted("msg-completed");
            }

            // 2. Second session (simulating app restart): reopen database
            try (SqliteDeliveryOutbox outbox2 = new SqliteDeliveryOutbox(dbPath)) {
                List<OutboxEntry> pending = outbox2.findPending();
                assertEquals(2, pending.size(), "Pending messages must survive restart");
                assertEquals("msg-durable-1", pending.get(0).messageId());
                assertEquals("msg-durable-2", pending.get(1).messageId());

                // Verify completed entry also survived in its completed state
                Optional<OutboxEntry> completed = outbox2.findByMessageId("msg-completed");
                assertTrue(completed.isPresent());
                assertEquals(OutboxState.COMPLETED, completed.get().state());
            }
        }
    }
}