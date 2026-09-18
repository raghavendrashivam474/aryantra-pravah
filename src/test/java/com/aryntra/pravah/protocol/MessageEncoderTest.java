package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MessageEncoderTest {

    @Test
    void testEncodeNullMessageThrows() {
        assertThrows(NullPointerException.class, () -> MessageEncoder.encode(null));
    }

    @Test
    void testEncodeHeaderFields() {
        Message msg = new Message(MessageType.JOIN, "a", "m1", new byte[0]);
        byte[] bytes = MessageEncoder.encode(msg);

        // Header Structure assertions
        assertTrue(bytes.length >= 12);
        assertEquals((byte) 'P', bytes[0]);
        assertEquals((byte) 'R', bytes[1]);
        assertEquals((byte) 0x01, bytes[2]); // version
        assertEquals((byte) 0x01, bytes[3]); // JOIN type code
    }

    @Test
    void testEncoderDeterminism() {
        Message msg = new Message(MessageType.MESSAGE, "alice-id", "msg-id-999", "Hello World!".getBytes(StandardCharsets.UTF_8));

        byte[] firstRun = MessageEncoder.encode(msg);
        byte[] secondRun = MessageEncoder.encode(msg);
        byte[] thirdRun = MessageEncoder.encode(msg);

        assertArrayEquals(firstRun, secondRun);
        assertArrayEquals(firstRun, thirdRun);
    }
}