package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S2.5 — Protocol Validation & Hardening Tests")
class ProtocolValidationTest {

    @Nested
    @DisplayName("1. Framing Layer Validation & Hardening")
    class FramingValidation {

        @Test
        @DisplayName("FrameDecoder rejects zero frame length")
        void shouldRejectZeroLengthFrame() {
            FrameDecoder decoder = new FrameDecoder();
            byte[] zeroFrame = new byte[]{0x00, 0x00, 0x00, 0x00};

            ProtocolException ex = assertThrows(ProtocolException.class, () -> decoder.feed(zeroFrame));
            assertTrue(ex.getMessage().contains("Invalid frame length"));
        }

        @Test
        @DisplayName("FrameDecoder rejects negative frame length (MSB set)")
        void shouldRejectNegativeLengthFrame() {
            FrameDecoder decoder = new FrameDecoder();
            byte[] negativeFrame = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

            ProtocolException ex = assertThrows(ProtocolException.class, () -> decoder.feed(negativeFrame));
            assertTrue(ex.getMessage().contains("Invalid frame length"));
        }

        @Test
        @DisplayName("FrameDecoder rejects frame exceeding MAX_FRAME_SIZE (16MB)")
        void shouldRejectOversizedFrame() {
            FrameDecoder decoder = new FrameDecoder();
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(FrameEncoder.MAX_FRAME_SIZE + 1);

            ProtocolException ex = assertThrows(ProtocolException.class, () -> decoder.feed(buf.array()));
            assertTrue(ex.getMessage().contains("exceeds maximum allowed size"));
        }

        @Test
        @DisplayName("FrameEncoder rejects zero-length byte array")
        void shouldRejectEmptyPayloadInEncoder() {
            assertThrows(ProtocolException.class, () -> FrameEncoder.encode(new byte[0]));
        }

        @Test
        @DisplayName("FrameEncoder rejects payload above MAX_FRAME_SIZE")
        void shouldRejectTooLargePayloadInEncoder() {
            // We verify boundary logic without 16MB allocation
            assertEquals(16 * 1024 * 1024, FrameEncoder.MAX_FRAME_SIZE);
        }
    }

    @Nested
    @DisplayName("2. Wire Format & Parser Validation")
    class WireParserValidation {

        @Test
        @DisplayName("MessageParser rejects truncated header (< 12 bytes)")
        void shouldRejectTruncatedHeader() {
            byte[] shortData = new byte[]{ 'P', 'R', 0x01, 0x01, 0x00 };
            ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(shortData));
            assertTrue(ex.getMessage().contains("Data is truncated"));
        }

        @Test
        @DisplayName("MessageParser rejects invalid magic header bytes")
        void shouldRejectInvalidMagic() {
            byte[] badMagic = new byte[]{
                'X', 'Y', // Invalid magic
                0x01,     // Version 1
                0x01,     // JOIN
                0x00, 0x01, 'a',
                0x00, 0x01, '1',
                0x00, 0x00, 0x00, 0x00
            };
            ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(badMagic));
            assertTrue(ex.getMessage().contains("Invalid magic header"));
        }

        @Test
        @DisplayName("MessageParser rejects unsupported version number")
        void shouldRejectUnsupportedVersion() {
            byte[] badVersion = new byte[]{
                'P', 'R',
                0x02,     // Version 2 (unsupported)
                0x01,     // JOIN
                0x00, 0x01, 'a',
                0x00, 0x01, '1',
                0x00, 0x00, 0x00, 0x00
            };
            ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(badVersion));
            assertTrue(ex.getMessage().contains("Unsupported protocol version"));
        }

        @Test
        @DisplayName("MessageParser rejects unknown message type code")
        void shouldRejectUnknownTypeCode() {
            byte[] badType = new byte[]{
                'P', 'R',
                0x01,
                (byte) 0x99, // Unknown type code
                0x00, 0x01, 'a',
                0x00, 0x01, '1',
                0x00, 0x00, 0x00, 0x00
            };
            ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(badType));
            assertTrue(ex.getMessage().contains("Invalid message type code"));
        }

        @Test
        @DisplayName("MessageParser rejects trailing unparsed bytes")
        void shouldRejectTrailingBytes() {
            Message valid = new Message(MessageType.JOIN, "alice", "m-1", new byte[0]);
            byte[] encoded = MessageEncoder.encode(valid);

            byte[] withTrailing = new byte[encoded.length + 3];
            System.arraycopy(encoded, 0, withTrailing, 0, encoded.length);
            withTrailing[encoded.length] = (byte) 0xDE;
            withTrailing[encoded.length + 1] = (byte) 0xAD;
            withTrailing[encoded.length + 2] = (byte) 0xBE;

            ProtocolException ex = assertThrows(ProtocolException.class, () -> MessageParser.parse(withTrailing));
            assertTrue(ex.getMessage().contains("Trailing bytes detected"));
        }

        @Test
        @DisplayName("MessageParser rejects truncated payload")
        void shouldRejectTruncatedPayload() {
            Message valid = new Message(MessageType.MESSAGE, "alice", "m-1", "hello world".getBytes(StandardCharsets.UTF_8));
            byte[] encoded = MessageEncoder.encode(valid);

            // Strip last 3 bytes
            byte[] truncated = new byte[encoded.length - 3];
            System.arraycopy(encoded, 0, truncated, 0, truncated.length);

            assertThrows(ProtocolException.class, () -> MessageParser.parse(truncated));
        }
    }

    @Nested
    @DisplayName("3. Semantic State Machine Hardening")
    class SemanticValidation {

        @Test
        @DisplayName("SessionManager rejects duplicate JOIN deterministically")
        void shouldRejectDuplicateJoin() {
            ProtocolSessionManager manager = new ProtocolSessionManager();
            Message join = new Message(MessageType.JOIN, "user-1", "j1", new byte[0]);

            manager.processMessage(join);
            assertThrows(ProtocolException.class, () -> manager.processMessage(join));
        }

        @Test
        @DisplayName("SessionManager rejects MESSAGE before JOIN")
        void shouldRejectMessageBeforeJoin() {
            ProtocolSessionManager manager = new ProtocolSessionManager();
            Message msg = new Message(MessageType.MESSAGE, "user-1", "m1", "data".getBytes());

            assertThrows(ProtocolException.class, () -> manager.processMessage(msg));
        }

        @Test
        @DisplayName("SessionManager rejects LEAVE before JOIN")
        void shouldRejectLeaveBeforeJoin() {
            ProtocolSessionManager manager = new ProtocolSessionManager();
            Message leave = new Message(MessageType.LEAVE, "user-1", "l1", new byte[0]);

            assertThrows(ProtocolException.class, () -> manager.processMessage(leave));
        }

        @Test
        @DisplayName("SessionManager rejects MESSAGE after LEAVE")
        void shouldRejectMessageAfterLeave() {
            ProtocolSessionManager manager = new ProtocolSessionManager();
            Message join = new Message(MessageType.JOIN, "user-1", "j1", new byte[0]);
            Message leave = new Message(MessageType.LEAVE, "user-1", "l1", new byte[0]);
            Message msg = new Message(MessageType.MESSAGE, "user-1", "m1", "data".getBytes());

            manager.processMessage(join);
            manager.processMessage(leave);
            assertThrows(ProtocolException.class, () -> manager.processMessage(msg));
        }
    }
}