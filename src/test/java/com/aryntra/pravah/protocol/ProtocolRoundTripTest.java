package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolRoundTripTest {

    private void assertRoundTrip(Message original) {
        byte[] encoded = MessageEncoder.encode(original);
        Message parsed = MessageParser.parse(encoded);

        assertEquals(original, parsed);
        assertEquals(original.type(), parsed.type());
        assertEquals(original.senderId(), parsed.senderId());
        assertEquals(original.messageId(), parsed.messageId());
        assertArrayEquals(original.payload(), parsed.payload());
    }

    @Test
    void testJoinMessageRoundTrip() {
        Message msg = new Message(MessageType.JOIN, "user-abc-123", "join-tx-01", new byte[0]);
        assertRoundTrip(msg);
    }

    @Test
    void testChatMessageRoundTrip() {
        byte[] payload = "Hello world! This is a secure network message testing block. Pravah Phase 2!".getBytes(StandardCharsets.UTF_8);
        Message msg = new Message(MessageType.MESSAGE, "bob-sender", "msg-uuid-9998273", payload);
        assertRoundTrip(msg);
    }

    @Test
    void testLeaveMessageRoundTrip() {
        Message msg = new Message(MessageType.LEAVE, "alice-id", "leave-tx-45", "Reason: connection lost".getBytes(StandardCharsets.UTF_8));
        assertRoundTrip(msg);
    }

    @Test
    void testUnicodeAndSpecialPayloadRoundTrip() {
        byte[] unicodePayload = "⚡ Pravah 🔥 🇨🇭 🚀 🧪".getBytes(StandardCharsets.UTF_8);
        Message msg = new Message(MessageType.MESSAGE, "✨unicode_sender✨", "🔥msg_id🔥", unicodePayload);
        assertRoundTrip(msg);
    }

    @Test
    void testVaryingPayloadSizes() {
        int[] sizes = { 0, 1, 127, 256, 1024, 65535 };
        for (int size : sizes) {
            byte[] payload = new byte[size];
            // Populate some non-zero byte values
            for (int i = 0; i < size; i++) {
                payload[i] = (byte) (i % 256);
            }
            Message msg = new Message(MessageType.MESSAGE, "peer-x", "mid-" + size, payload);
            assertRoundTrip(msg);
        }
    }
}