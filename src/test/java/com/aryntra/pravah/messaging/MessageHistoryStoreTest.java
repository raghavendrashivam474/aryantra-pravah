package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for MessageHistoryStore.
 * Uses InMemoryMessageHistoryStore to verify the interface contract.
 */
class MessageHistoryStoreTest {

    private MessageHistoryStore store;
    private PeerId alice;
    private PeerId bob;
    private ConversationId directConv;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessageHistoryStore();
        alice = PeerId.of("alice");
        bob = PeerId.of("bob");
        directConv = ConversationManager.deriveDirectConversationId(alice, bob);
    }

    @Nested
    @DisplayName("Save and Retrieve")
    class SaveAndRetrieve {

        @Test
        @DisplayName("saved message can be found by messageId")
        void saveAndFind() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "Hello Bob", directConv);
            store.save(msg, MessageState.CREATED);

            var found = store.find(msg.messageId());
            assertTrue(found.isPresent());
            assertEquals(msg.messageId(), found.get().messageId());
            assertEquals(alice, found.get().sender());
            assertEquals("Hello Bob", found.get().content());
            assertEquals(directConv, found.get().conversationId());
        }

        @Test
        @DisplayName("find returns empty for nonexistent messageId")
        void findMissing() {
            assertTrue(store.find("does-not-exist").isEmpty());
        }

        @Test
        @DisplayName("duplicate messageId is rejected")
        void duplicateIdRejected() {
            Instant now = Instant.now();
            ApplicationMessage m1 = new ApplicationMessage("dup-1", alice, "First", now, directConv);
            ApplicationMessage m2 = new ApplicationMessage("dup-1", bob, "Second", now, directConv);
            store.save(m1, MessageState.CREATED);

            assertThrows(IllegalArgumentException.class,
                    () -> store.save(m2, MessageState.SENT));
        }

        @Test
        @DisplayName("null message is rejected")
        void nullMessageRejected() {
            assertThrows(NullPointerException.class,
                    () -> store.save(null, MessageState.CREATED));
        }

        @Test
        @DisplayName("null state is rejected")
        void nullStateRejected() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "test", directConv);
            assertThrows(NullPointerException.class,
                    () -> store.save(msg, null));
        }
    }

    @Nested
    @DisplayName("Conversation History")
    class HistoryTests {

        @Test
        @DisplayName("returns all messages for a conversation in insertion order")
        void orderedHistory() {
            Instant base = Instant.now();
            ApplicationMessage m1 = new ApplicationMessage("h1", alice, "First", base, directConv);
            ApplicationMessage m2 = new ApplicationMessage("h2", bob, "Second", base, directConv);
            ApplicationMessage m3 = new ApplicationMessage("h3", alice, "Third", base, directConv);

            store.save(m1, MessageState.SENT);
            store.save(m2, MessageState.DELIVERED);
            store.save(m3, MessageState.CREATED);

            List<ApplicationMessage> history = store.getConversationHistory(directConv);
            assertEquals(3, history.size());
            assertEquals("First", history.get(0).content());
            assertEquals("Second", history.get(1).content());
            assertEquals("Third", history.get(2).content());
        }

        @Test
        @DisplayName("excludes messages from other conversations")
        void filtersByConversation() {
            ConversationId other = new ConversationId("direct:charlie:dave");
            store.save(ApplicationMessage.text(alice, "In", directConv), MessageState.SENT);
            store.save(ApplicationMessage.text(alice, "Out", other), MessageState.SENT);

            List<ApplicationMessage> history = store.getConversationHistory(directConv);
            assertEquals(1, history.size());
            assertEquals("In", history.get(0).content());
        }

        @Test
        @DisplayName("empty conversation returns empty list")
        void emptyHistory() {
            List<ApplicationMessage> history = store.getConversationHistory(directConv);
            assertNotNull(history);
            assertTrue(history.isEmpty());
        }
    }

    @Nested
    @DisplayName("State Tracking")
    class StateTests {

        @Test
        @DisplayName("state is persisted on save and queryable")
        void stateOnSave() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "Track", directConv);
            store.save(msg, MessageState.CREATED);

            assertEquals(MessageState.CREATED, store.findState(msg.messageId()).orElseThrow());
        }

        @Test
        @DisplayName("updateState transitions through lifecycle")
        void stateTransitions() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "Lifecycle", directConv);
            store.save(msg, MessageState.CREATED);

            store.updateState(msg.messageId(), MessageState.SENT);
            assertEquals(MessageState.SENT, store.findState(msg.messageId()).orElseThrow());

            store.updateState(msg.messageId(), MessageState.DELIVERED);
            assertEquals(MessageState.DELIVERED, store.findState(msg.messageId()).orElseThrow());
        }

        @Test
        @DisplayName("updateState for missing message throws")
        void updateMissingThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.updateState("ghost", MessageState.FAILED));
        }

        @Test
        @DisplayName("findState for missing message returns empty")
        void findStateMissing() {
            assertTrue(store.findState("ghost").isEmpty());
        }
    }

    @Nested
    @DisplayName("Delete")
    class DeleteTests {

        @Test
        @DisplayName("delete removes message and state")
        void deleteRemoves() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "Gone", directConv);
            store.save(msg, MessageState.SENT);
            store.delete(msg.messageId());

            assertTrue(store.find(msg.messageId()).isEmpty());
            assertTrue(store.findState(msg.messageId()).isEmpty());
        }

        @Test
        @DisplayName("delete of nonexistent message is a no-op")
        void deleteMissingNoOp() {
            assertDoesNotThrow(() -> store.delete("no-such-id"));
        }
    }
}