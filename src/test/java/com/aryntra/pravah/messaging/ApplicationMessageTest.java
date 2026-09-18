package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.1 & S4.2 ApplicationMessage Domain Tests")
class ApplicationMessageTest {

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");
    private final ConversationId convId = new ConversationId("conv-xyz");

    @Nested
    @DisplayName("Validation & Construction")
    class ValidationTests {

        @Test
        @DisplayName("Should create valid text message via factory linked to conversation")
        void validTextMessageWithConversation() {
            ApplicationMessage msg = ApplicationMessage.text(alice, "Hello Bob", convId);
            assertNotNull(msg.messageId());
            assertFalse(msg.messageId().isBlank());
            assertEquals(alice, msg.sender());
            assertEquals("Hello Bob", msg.content());
            assertEquals(convId, msg.conversationId());
            assertNotNull(msg.timestamp());
            assertArrayEquals("Hello Bob".getBytes(StandardCharsets.UTF_8), msg.toPayload());
        }

        @Test
        @DisplayName("Should create text message with explicit messageId")
        void explicitMessageId() {
            ApplicationMessage msg = ApplicationMessage.text("msg-123", alice, "Custom ID message");
            assertEquals("msg-123", msg.messageId());
            assertEquals(alice, msg.sender());
            assertEquals("Custom ID message", msg.content());
            assertNotNull(msg.conversationId());
        }

        @Test
        @DisplayName("Should reconstruct from payload bytes with conversation")
        void fromPayloadTest() {
            byte[] payload = "Incoming payload".getBytes(StandardCharsets.UTF_8);
            ApplicationMessage msg = ApplicationMessage.fromPayload("msg-456", bob, payload, convId);
            assertEquals("msg-456", msg.messageId());
            assertEquals(bob, msg.sender());
            assertEquals("Incoming payload", msg.content());
            assertEquals(convId, msg.conversationId());
        }

        @Test
        @DisplayName("Should reject null or blank parameters")
        void rejectInvalidParams() {
            assertThrows(NullPointerException.class, () -> new ApplicationMessage(null, alice, "text", Instant.now(), convId));
            assertThrows(IllegalArgumentException.class, () -> new ApplicationMessage("  ", alice, "text", Instant.now(), convId));
            assertThrows(NullPointerException.class, () -> new ApplicationMessage("id", null, "text", Instant.now(), convId));
            assertThrows(NullPointerException.class, () -> new ApplicationMessage("id", alice, null, Instant.now(), convId));
            assertThrows(NullPointerException.class, () -> new ApplicationMessage("id", alice, "text", null, convId));
            assertThrows(NullPointerException.class, () -> new ApplicationMessage("id", alice, "text", Instant.now(), null));
            assertThrows(NullPointerException.class, () -> ApplicationMessage.fromPayload("id", alice, null, convId));
        }
    }

    @Nested
    @DisplayName("Equality & Value Semantics")
    class EqualityTests {

        @Test
        @DisplayName("Messages with same id, sender, content, and conversationId are equal")
        void messageEquality() {
            Instant t1 = Instant.ofEpochMilli(1000);
            Instant t2 = Instant.ofEpochMilli(2000);

            ApplicationMessage msg1 = new ApplicationMessage("msg-1", alice, "Hello", t1, convId);
            ApplicationMessage msg2 = new ApplicationMessage("msg-1", alice, "Hello", t2, convId);
            ApplicationMessage msg3 = new ApplicationMessage("msg-2", alice, "Hello", t1, convId);

            assertEquals(msg1, msg2);
            assertEquals(msg1.hashCode(), msg2.hashCode());
            assertNotEquals(msg1, msg3);
        }
    }
}