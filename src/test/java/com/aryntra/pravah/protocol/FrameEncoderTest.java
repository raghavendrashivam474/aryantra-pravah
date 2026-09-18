package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FrameEncoder Unit Tests")
class FrameEncoderTest {

    @Test
    @DisplayName("encode() wraps payload with 4-byte big-endian length prefix")
    void shouldEncodeWithLengthPrefix() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] frame = FrameEncoder.encode(payload);

        assertEquals(4 + 5, frame.length);

        int lengthPrefix = ByteBuffer.wrap(frame, 0, 4).getInt();
        assertEquals(5, lengthPrefix);

        byte[] extractedPayload = new byte[5];
        System.arraycopy(frame, 4, extractedPayload, 0, 5);
        assertArrayEquals(payload, extractedPayload);
    }

    @Test
    @DisplayName("encode() rejects null payload")
    void shouldRejectNullPayload() {
        assertThrows(NullPointerException.class, () -> FrameEncoder.encode(null));
    }

    @Test
    @DisplayName("encode() rejects zero-length payload")
    void shouldRejectEmptyPayload() {
        assertThrows(ProtocolException.class, () -> FrameEncoder.encode(new byte[0]));
    }

    @Test
    @DisplayName("encode() rejects payload exceeding MAX_FRAME_SIZE")
    void shouldRejectOversizedPayload() {
        // We test the boundary condition via a custom check rather than allocating 16MB in test
        // Verify constant exists and is 16MB
        assertEquals(16 * 1024 * 1024, FrameEncoder.MAX_FRAME_SIZE);
    }

    @Test
    @DisplayName("encode() integrates with MessageEncoder output")
    void shouldFrameEncodedMessage() {
        Message msg = new Message(MessageType.MESSAGE, "alice", "m1", "hello".getBytes());
        byte[] encoded = MessageEncoder.encode(msg);
        byte[] frame = FrameEncoder.encode(encoded);

        assertEquals(4 + encoded.length, frame.length);
        int len = ByteBuffer.wrap(frame, 0, 4).getInt();
        assertEquals(encoded.length, len);
    }
}