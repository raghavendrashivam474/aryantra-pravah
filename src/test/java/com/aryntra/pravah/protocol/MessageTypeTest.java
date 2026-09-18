package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTypeTest {

    @Test
    void testEnumCodes() {
        assertEquals((byte) 0x01, MessageType.JOIN.code());
        assertEquals((byte) 0x02, MessageType.MESSAGE.code());
        assertEquals((byte) 0x03, MessageType.LEAVE.code());
    }

    @Test
    void testFromCodeSuccess() {
        assertEquals(MessageType.JOIN, MessageType.fromCode((byte) 0x01));
        assertEquals(MessageType.MESSAGE, MessageType.fromCode((byte) 0x02));
        assertEquals(MessageType.LEAVE, MessageType.fromCode((byte) 0x03));
    }

    @Test
    void testFromCodeFailure() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            MessageType.fromCode((byte) 0x99);
        });
        assertTrue(ex.getMessage().contains("Unknown MessageType code: 0x99"));
    }
}