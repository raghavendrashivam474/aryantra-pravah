package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OutboxEntry Tests")
class OutboxEntryTest {

    private final PeerId bob = PeerId.of("bob");
    private final ConversationId convId = new ConversationId("direct:alice:bob");

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null messageId")
        void rejectsNullMessageId() {
            assertThrows(NullPointerException.class,
                    () -> new OutboxEntry(null, bob, convId, OutboxState.PENDING, Instant.now()));
        }

        @Test
        @DisplayName("Rejects blank messageId")
        void rejectsBlankMessageId() {
            assertThrows(IllegalArgumentException.class,
                    () -> new OutboxEntry("  ", bob, convId, OutboxState.PENDING, Instant.now()));
        }

        @Test
        @DisplayName("Rejects null destination")
        void rejectsNullDestination() {
            assertThrows(NullPointerException.class,
                    () -> new OutboxEntry("msg-1", null, convId, OutboxState.PENDING, Instant.now()));
        }

        @Test
        @DisplayName("Rejects null conversationId")
        void rejectsNullConversationId() {
            assertThrows(NullPointerException.class,
                    () -> new OutboxEntry("msg-1", bob, null, OutboxState.PENDING, Instant.now()));
        }

        @Test
        @DisplayName("Rejects null state")
        void rejectsNullState() {
            assertThrows(NullPointerException.class,
                    () -> new OutboxEntry("msg-1", bob, convId, null, Instant.now()));
        }

        @Test
        @DisplayName("Rejects null createdAt")
        void rejectsNullCreatedAt() {
            assertThrows(NullPointerException.class,
                    () -> new OutboxEntry("msg-1", bob, convId, OutboxState.PENDING, null));
        }
    }

    @Nested
    @DisplayName("Behavior & Equality")
    class BehaviorTests {

        @Test
        @DisplayName("pending factory creates valid entry in PENDING state")
        void pendingFactoryCreatesValidEntry() {
            OutboxEntry entry = OutboxEntry.pending("msg-1", bob, convId);

            assertEquals("msg-1", entry.messageId());
            assertEquals(bob, entry.destination());
            assertEquals(convId, entry.conversationId());
            assertEquals(OutboxState.PENDING, entry.state());
            assertNotNull(entry.createdAt());
        }

        @Test
        @DisplayName("withState returns updated copy preserving other fields")
        void withStateReturnsUpdatedCopy() {
            OutboxEntry original = OutboxEntry.pending("msg-1", bob, convId);
            OutboxEntry updated = original.withState(OutboxState.COMPLETED);

            assertEquals(OutboxState.COMPLETED, updated.state());
            assertEquals(original.messageId(), updated.messageId());
            assertEquals(original.destination(), updated.destination());
            assertEquals(original.conversationId(), updated.conversationId());
            assertEquals(original.createdAt(), updated.createdAt());
        }

        @Test
        @DisplayName("Equality is based on messageId")
        void equalityBasedOnMessageId() {
            OutboxEntry e1 = OutboxEntry.pending("msg-1", bob, convId);
            OutboxEntry e2 = new OutboxEntry("msg-1", PeerId.of("charlie"),
                    new ConversationId("direct:alice:charlie"), OutboxState.COMPLETED, Instant.now());
            OutboxEntry e3 = OutboxEntry.pending("msg-2", bob, convId);

            assertEquals(e1, e2);
            assertEquals(e1.hashCode(), e2.hashCode());
            assertNotEquals(e1, e3);
        }
    }
}
