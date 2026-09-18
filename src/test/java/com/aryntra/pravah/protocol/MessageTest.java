package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void testSuccessfulMessageConstruction() {
        byte[] payload = "Hello Pravah".getBytes(StandardCharsets.UTF_8);
        Message msg = new Message(MessageType.MESSAGE, "alice", "msg-123", payload);

        assertEquals(MessageType.MESSAGE, msg.type());
        assertEquals("alice", msg.senderId());
        assertEquals("msg-123", msg.messageId());
        assertArrayEquals(payload, msg.payload());
    }

    @Test
    void testNullValidationRejections() {
        byte[] validPayload = new byte[0];

        assertThrows(NullPointerException.class, () -> 
            new Message(null, "alice", "msg-123", validPayload)
        );
        assertThrows(NullPointerException.class, () -> 
            new Message(MessageType.MESSAGE, null, "msg-123", validPayload)
        );
        assertThrows(NullPointerException.class, () -> 
            new Message(MessageType.MESSAGE, "alice", null, validPayload)
        );
        assertThrows(NullPointerException.class, () -> 
            new Message(MessageType.MESSAGE, "alice", "msg-123", null)
        );
    }

    @Test
    void testBlankFieldValidationRejections() {
        byte[] emptyPayload = new byte[0];

        assertThrows(IllegalArgumentException.class, () -> 
            new Message(MessageType.MESSAGE, "", "msg-123", emptyPayload)
        );
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(MessageType.MESSAGE, "  ", "msg-123", emptyPayload)
        );
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(MessageType.MESSAGE, "alice", "", emptyPayload)
        );
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(MessageType.MESSAGE, "alice", "  ", emptyPayload)
        );
    }

    @Test
    void testDefensiveCopying() {
        byte[] mutablePayload = { 1, 2, 3 };
        Message msg = new Message(MessageType.MESSAGE, "alice", "msg-123", mutablePayload);

        // Mutate initial array
        mutablePayload[0] = 99;
        // Internal message payload should remain intact
        assertArrayEquals(new byte[] { 1, 2, 3 }, msg.payload());

        // Mutate array returned by getter
        byte[] returnedPayload = msg.payload();
        returnedPayload[1] = 88;
        // Internal message payload should remain intact
        assertArrayEquals(new byte[] { 1, 2, 3 }, msg.payload());
    }

    @Test
    void testValueBasedEqualityAndHashCode() {
        byte[] payload1 = { 1, 2, 3 };
        byte[] payload2 = { 1, 2, 3 };

        Message msg1 = new Message(MessageType.MESSAGE, "alice", "msg-123", payload1);
        Message msg2 = new Message(MessageType.MESSAGE, "alice", "msg-123", payload2);
        Message msgDiffPayload = new Message(MessageType.MESSAGE, "alice", "msg-123", new byte[] { 4, 5 });
        Message msgDiffSender = new Message(MessageType.MESSAGE, "bob", "msg-123", payload1);

        // Standard equals rules
        assertEquals(msg1, msg2);
        assertEquals(msg1.hashCode(), msg2.hashCode());

        assertNotEquals(msg1, msgDiffPayload);
        assertNotEquals(msg1, msgDiffSender);
        assertNotEquals(null, msg1);
        assertNotEquals("string object", msg1);
    }
}