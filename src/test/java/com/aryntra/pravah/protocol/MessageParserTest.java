package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageParserTest {

    @Test
    void testParseNullDataThrows() {
        assertThrows(NullPointerException.class, () -> MessageParser.parse(null));
    }

    @Test
    void testParseTruncatedDataThrows() {
        byte[] tiny = new byte[5];
        ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(tiny));
        assertTrue(ex.getMessage().contains("truncated") || ex.getMessage().contains("smaller than minimal"));
    }

    @Test
    void testParseInvalidMagicThrows() {
        byte[] invalidMagic = {
            'X', 'Y', // Bad Magic
            0x01,     // Version
            0x01,     // Type JOIN
            0x00, 0x01, 'a', // Sender ID
            0x00, 0x01, 'b', // Message ID
            0x00, 0x00, 0x00, 0x00 // Payload Length (0)
        };

        ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(invalidMagic));
        assertTrue(ex.getMessage().contains("Invalid magic header"));
    }

    @Test
    void testParseUnsupportedVersionThrows() {
        byte[] invalidVersion = {
            'P', 'R', // Magic
            0x09,     // Unsupported Version 9
            0x01,     // Type JOIN
            0x00, 0x01, 'a', // Sender ID
            0x00, 0x01, 'b', // Message ID
            0x00, 0x00, 0x00, 0x00 // Payload Length (0)
        };

        ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(invalidVersion));
        assertTrue(ex.getMessage().contains("Unsupported protocol version"));
    }

    @Test
    void testParseUnknownMessageTypeThrows() {
        byte[] unknownType = {
            'P', 'R', // Magic
            0x01,     // Version
            0x7F,     // Type 0x7F (Unknown)
            0x00, 0x01, 'a', // Sender ID
            0x00, 0x01, 'b', // Message ID
            0x00, 0x00, 0x00, 0x00 // Payload Length (0)
        };

        ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(unknownType));
        assertTrue(ex.getMessage().contains("Invalid message type code") || ex.getMessage().contains("Unknown MessageType code"));
    }

    @Test
    void testTrailingBytesRejection() {
        Message msg = new Message(MessageType.MESSAGE, "alice", "msg-1", new byte[] { 10, 20 });
        byte[] encoded = MessageEncoder.encode(msg);

        // Create dirty array with trailing byte garbage
        byte[] dirty = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, dirty, 0, encoded.length);
        dirty[dirty.length - 1] = 0x55; // Garbage trail

        ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(dirty));
        assertTrue(ex.getMessage().contains("Trailing bytes detected"));
    }
}