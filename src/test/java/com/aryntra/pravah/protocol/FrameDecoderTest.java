package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FrameDecoder Unit Tests")
class FrameDecoderTest {

    private FrameDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new FrameDecoder();
    }

    @Test
    @DisplayName("feed() extracts a single complete frame")
    void shouldExtractSingleFrame() {
        byte[] payload = new byte[]{10, 20, 30};
        byte[] frame = FrameEncoder.encode(payload);

        List<byte[]> result = decoder.feed(frame);

        assertEquals(1, result.size());
        assertArrayEquals(payload, result.get(0));
        assertEquals(0, decoder.getBufferedBytes());
    }

    @Test
    @DisplayName("feed() handles partial frames across multiple reads")
    void shouldHandlePartialFrames() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] frame = FrameEncoder.encode(payload); // 4 bytes header + 8 bytes payload = 12 bytes

        // Split into 3 chunks: [4B header + 2B], [3B], [3B]
        byte[] chunk1 = Arrays.copyOfRange(frame, 0, 6);
        byte[] chunk2 = Arrays.copyOfRange(frame, 6, 9);
        byte[] chunk3 = Arrays.copyOfRange(frame, 9, 12);

        List<byte[]> r1 = decoder.feed(chunk1);
        assertTrue(r1.isEmpty());
        assertEquals(6, decoder.getBufferedBytes());

        List<byte[]> r2 = decoder.feed(chunk2);
        assertTrue(r2.isEmpty());
        assertEquals(9, decoder.getBufferedBytes());

        List<byte[]> r3 = decoder.feed(chunk3);
        assertEquals(1, r3.size());
        assertArrayEquals(payload, r3.get(0));
        assertEquals(0, decoder.getBufferedBytes());
    }

    @Test
    @DisplayName("feed() extracts multiple frames from a single coalesced read")
    void shouldExtractMultipleFramesInSingleRead() {
        byte[] p1 = new byte[]{1, 2};
        byte[] p2 = new byte[]{3, 4, 5};
        byte[] p3 = new byte[]{6};

        byte[] f1 = FrameEncoder.encode(p1);
        byte[] f2 = FrameEncoder.encode(p2);
        byte[] f3 = FrameEncoder.encode(p3);

        // Coalesce all 3 into one buffer
        ByteBuffer combined = ByteBuffer.allocate(f1.length + f2.length + f3.length);
        combined.put(f1).put(f2).put(f3);

        List<byte[]> result = decoder.feed(combined.array());

        assertEquals(3, result.size());
        assertArrayEquals(p1, result.get(0));
        assertArrayEquals(p2, result.get(1));
        assertArrayEquals(p3, result.get(2));
        assertEquals(0, decoder.getBufferedBytes());
    }

    @Test
    @DisplayName("feed() handles mixed: partial frame + full frame + partial next")
    void shouldHandleMixedPartialAndFullFrames() {
        byte[] p1 = new byte[]{10, 20};
        byte[] p2 = new byte[]{30, 40, 50};

        byte[] f1 = FrameEncoder.encode(p1); // 6 bytes
        byte[] f2 = FrameEncoder.encode(p2); // 7 bytes

        // Send: first 3 bytes of f1
        List<byte[]> r1 = decoder.feed(Arrays.copyOfRange(f1, 0, 3));
        assertTrue(r1.isEmpty());

        // Send: rest of f1 (3B) + entire f2 (7B) = 10B
        ByteBuffer chunk2 = ByteBuffer.allocate(3 + f2.length);
        chunk2.put(Arrays.copyOfRange(f1, 3, 6));
        chunk2.put(f2);

        List<byte[]> r2 = decoder.feed(chunk2.array());
        assertEquals(2, r2.size());
        assertArrayEquals(p1, r2.get(0));
        assertArrayEquals(p2, r2.get(1));
        assertEquals(0, decoder.getBufferedBytes());
    }

    @Test
    @DisplayName("feed() handles header split across reads (1 byte at a time)")
    void shouldHandleHeaderSplitByteByByte() {
        byte[] payload = new byte[]{42};
        byte[] frame = FrameEncoder.encode(payload); // 5 bytes total

        for (int i = 0; i < frame.length - 1; i++) {
            List<byte[]> r = decoder.feed(new byte[]{frame[i]});
            assertTrue(r.isEmpty());
        }

        List<byte[]> last = decoder.feed(new byte[]{frame[frame.length - 1]});
        assertEquals(1, last.size());
        assertArrayEquals(payload, last.get(0));
    }

    @Test
    @DisplayName("feed() ignores empty chunks")
    void shouldIgnoreEmptyChunks() {
        List<byte[]> r = decoder.feed(new byte[0]);
        assertTrue(r.isEmpty());
        assertEquals(0, decoder.getBufferedBytes());
    }

    @Test
    @DisplayName("feed() rejects null input")
    void shouldRejectNullInput() {
        assertThrows(NullPointerException.class, () -> decoder.feed(null));
    }

    @Test
    @DisplayName("feed() rejects frame with length <= 0")
    void shouldRejectZeroOrNegativeFrameLength() {
        byte[] badFrame = new byte[]{0x00, 0x00, 0x00, 0x00}; // length = 0
        assertThrows(ProtocolException.class, () -> decoder.feed(badFrame));
    }

    @Test
    @DisplayName("feed() rejects frame exceeding MAX_FRAME_SIZE")
    void shouldRejectOversizedFrame() {
        // Encode a length prefix of 17MB (above 16MB limit)
        byte[] badHeader = new byte[]{0x01, 0x10, 0x00, 0x00}; // 17,825,792 bytes
        assertThrows(ProtocolException.class, () -> decoder.feed(badHeader));
    }

    @Test
    @DisplayName("reset() clears buffered state")
    void shouldClearBufferOnReset() {
        byte[] partial = new byte[]{0x00, 0x00, 0x00, 0x05, 0x01}; // 4B header + 1B payload (needs 4 more)
        decoder.feed(partial);
        assertEquals(5, decoder.getBufferedBytes());

        decoder.reset();
        assertEquals(0, decoder.getBufferedBytes());
    }

    @Test
    @DisplayName("Independent decoders maintain isolated state for separate peers")
    void shouldIsolateStateBetweenPeers() {
        FrameDecoder peerA = new FrameDecoder();
        FrameDecoder peerB = new FrameDecoder();

        byte[] payloadA = new byte[]{1, 2, 3};
        byte[] payloadB = new byte[]{4, 5, 6, 7};

        byte[] frameA = FrameEncoder.encode(payloadA);
        byte[] frameB = FrameEncoder.encode(payloadB);

        // Peer A sends partial data
        peerA.feed(Arrays.copyOfRange(frameA, 0, 4));
        assertEquals(4, peerA.getBufferedBytes());

        // Peer B sends complete data — should NOT be affected by A
        List<byte[]> resultB = peerB.feed(frameB);
        assertEquals(1, resultB.size());
        assertArrayEquals(payloadB, resultB.get(0));
        assertEquals(0, peerB.getBufferedBytes());

        // Now finish Peer A
        List<byte[]> resultA = peerA.feed(Arrays.copyOfRange(frameA, 4, frameA.length));
        assertEquals(1, resultA.size());
        assertArrayEquals(payloadA, resultA.get(0));
    }
}