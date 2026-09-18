package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMessageHistoryStoreTest {

    @TempDir
    Path tempDir;

    private Path dbFile;
    private SqliteMessageHistoryStore store;
    private PeerId alice;
    private PeerId bob;
    private ConversationId directConv;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("test_history.db");
        store = new SqliteMessageHistoryStore(dbFile);
        alice = PeerId.of("alice");
        bob = PeerId.of("bob");
        directConv = ConversationManager.deriveDirectConversationId(alice, bob);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("save and find returns exact reconstructed message")
        void saveAndFind() {
            Instant now = Instant.now();
            ApplicationMessage msg = new ApplicationMessage("msg-1", alice, "Hello SQLite", now, directConv);
            store.save(msg, MessageState.CREATED);

            var found = store.find("msg-1");
            assertTrue(found.isPresent());
            assertEquals("msg-1", found.get().messageId());
            assertEquals(alice, found.get().sender());
            assertEquals("Hello SQLite", found.get().content());
            assertEquals(directConv, found.get().conversationId());
            assertEquals(now.toEpochMilli(), found.get().timestamp().toEpochMilli());
        }

        @Test
        @DisplayName("duplicate messageId throws IllegalArgumentException")
        void duplicateMessageId() {
            ApplicationMessage m1 = ApplicationMessage.text(alice, "M1", directConv);
            ApplicationMessage m2 = new ApplicationMessage(m1.messageId(), bob, "M2", Instant.now(), directConv);

            store.save(m1, MessageState.SENT);
            assertThrows(IllegalArgumentException.class, () -> store.save(m2, MessageState.SENT));
        }

        @Test
        @DisplayName("update and query lifecycle state")
        void stateTransitions() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "State test", directConv);
            store.save(msg, MessageState.CREATED);
            assertEquals(MessageState.CREATED, store.findState(msg.messageId()).orElseThrow());

            store.updateState(msg.messageId(), MessageState.SENT);
            assertEquals(MessageState.SENT, store.findState(msg.messageId()).orElseThrow());

            store.updateState(msg.messageId(), MessageState.DELIVERED);
            assertEquals(MessageState.DELIVERED, store.findState(msg.messageId()).orElseThrow());
        }

        @Test
        @DisplayName("delete removes message and its state")
        void deleteMessage() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "To Delete", directConv);
            store.save(msg, MessageState.SENT);

            store.delete(msg.messageId());
            assertTrue(store.find(msg.messageId()).isEmpty());
            assertTrue(store.findState(msg.messageId()).isEmpty());
        }
    }

    @Nested
    @DisplayName("Ordering & History")
    class OrderingAndHistory {

        @Test
        @DisplayName("history preserves deterministic sequence order")
        void deterministicOrder() {
            Instant sameTimestamp = Instant.ofEpochMilli(1700000000000L);
            ApplicationMessage m1 = new ApplicationMessage("seq-1", alice, "First", sameTimestamp, directConv);
            ApplicationMessage m2 = new ApplicationMessage("seq-2", bob, "Second", sameTimestamp, directConv);
            ApplicationMessage m3 = new ApplicationMessage("seq-3", alice, "Third", sameTimestamp, directConv);

            store.save(m1, MessageState.SENT);
            store.save(m2, MessageState.DELIVERED);
            store.save(m3, MessageState.SENT);

            List<ApplicationMessage> history = store.getConversationHistory(directConv);
            assertEquals(3, history.size());
            assertEquals("seq-1", history.get(0).messageId());
            assertEquals("seq-2", history.get(1).messageId());
            assertEquals("seq-3", history.get(2).messageId());
        }
    }

    @Nested
    @DisplayName("Durability Across Reconnect / Reopen")
    class Durability {

        @Test
        @DisplayName("messages survive store closure and re-instantiation against the same file")
        void survivesRestart() {
            ApplicationMessage m1 = ApplicationMessage.text(alice, "Persisted 1", directConv);
            ApplicationMessage m2 = ApplicationMessage.text(bob, "Persisted 2", directConv);

            store.save(m1, MessageState.SENT);
            store.save(m2, MessageState.DELIVERED);

            // Close the database store (simulating process shutdown)
            store.close();

            // Re-open brand new store instance pointing to same file
            try (SqliteMessageHistoryStore newStore = new SqliteMessageHistoryStore(dbFile)) {
                List<ApplicationMessage> history = newStore.getConversationHistory(directConv);
                assertEquals(2, history.size());
                assertEquals(m1.messageId(), history.get(0).messageId());
                assertEquals("Persisted 1", history.get(0).content());
                assertEquals(MessageState.SENT, newStore.findState(m1.messageId()).orElseThrow());

                assertEquals(m2.messageId(), history.get(1).messageId());
                assertEquals("Persisted 2", history.get(1).content());
                assertEquals(MessageState.DELIVERED, newStore.findState(m2.messageId()).orElseThrow());
            }
        }
    }
}