package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SqliteGroupDeliveryOutbox Durability & Lifecycle Tests")
class SqliteGroupDeliveryOutboxTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private final PeerId bob = PeerId.of("bob");
    private final PeerId charlie = PeerId.of("charlie");
    private final ConversationId groupId = new ConversationId("group:dev-team");

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("group-outbox-test.db");
    }

    @Test
    @DisplayName("enqueueGroupMessage and findPendingRecipients")
    void testEnqueueAndFindPending() throws Exception {
        try (SqliteGroupDeliveryOutbox outbox = new SqliteGroupDeliveryOutbox(dbPath)) {
            outbox.enqueueGroupMessage("grp-msg-1", groupId, Set.of(bob, charlie));

            List<PeerId> pending = outbox.findPendingRecipients("grp-msg-1");
            assertEquals(2, pending.size());
            assertTrue(pending.contains(bob));
            assertTrue(pending.contains(charlie));

            assertEquals(OutboxState.PENDING, outbox.getRecipientState("grp-msg-1", bob));
            assertEquals(OutboxState.PENDING, outbox.getRecipientState("grp-msg-1", charlie));
        }
    }

    @Test
    @DisplayName("Per-recipient completion and allRecipientsCompleted check")
    void testRecipientCompletion() throws Exception {
        try (SqliteGroupDeliveryOutbox outbox = new SqliteGroupDeliveryOutbox(dbPath)) {
            outbox.enqueueGroupMessage("grp-msg-2", groupId, Set.of(bob, charlie));

            outbox.markRecipientCompleted("grp-msg-2", bob);

            assertEquals(OutboxState.COMPLETED, outbox.getRecipientState("grp-msg-2", bob));
            assertEquals(OutboxState.PENDING, outbox.getRecipientState("grp-msg-2", charlie));
            assertFalse(outbox.allRecipientsCompleted("grp-msg-2"));

            List<PeerId> pending = outbox.findPendingRecipients("grp-msg-2");
            assertEquals(1, pending.size());
            assertEquals(charlie, pending.get(0));

            outbox.markRecipientCompleted("grp-msg-2", charlie);
            assertTrue(outbox.allRecipientsCompleted("grp-msg-2"));
        }
    }

    @Test
    @DisplayName("Durability across restart preserves per-recipient states and attempt counts")
    void testDurabilityAcrossRestart() throws Exception {
        try (SqliteGroupDeliveryOutbox outbox = new SqliteGroupDeliveryOutbox(dbPath)) {
            outbox.enqueueGroupMessage("grp-msg-durable", groupId, Set.of(bob, charlie));
            outbox.markRecipientCompleted("grp-msg-durable", bob);
            outbox.incrementAttemptCount("grp-msg-durable", charlie);
        }

        // Reopen database to simulate application restart
        try (SqliteGroupDeliveryOutbox reopened = new SqliteGroupDeliveryOutbox(dbPath)) {
            assertEquals(OutboxState.COMPLETED, reopened.getRecipientState("grp-msg-durable", bob));
            assertEquals(OutboxState.PENDING, reopened.getRecipientState("grp-msg-durable", charlie));
            assertEquals(1, reopened.getAttemptCount("grp-msg-durable", charlie));
            assertEquals(0, reopened.getAttemptCount("grp-msg-durable", bob));

            List<String> pendingForCharlie = reopened.findPendingMessageIdsForPeer(charlie);
            assertEquals(1, pendingForCharlie.size());
            assertEquals("grp-msg-durable", pendingForCharlie.get(0));

            List<String> pendingForBob = reopened.findPendingMessageIdsForPeer(bob);
            assertTrue(pendingForBob.isEmpty());
        }
    }
}