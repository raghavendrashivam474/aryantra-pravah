package com.aryntra.pravah.protocol;

import java.util.Objects;

/**
 * Encodes a complete encoded message into a length-prefixed frame
 * suitable for transmission over a byte-stream transport such as TCP.
 *
 * <h3>Frame Format</h3>
 * <pre>
 * [FRAME_LEN: 4 bytes, big-endian][ENCODED_MESSAGE: FRAME_LEN bytes]
 * </pre>
 *
 * <p>FRAME_LEN is the byte length of the encoded message (the output of
 * {@link MessageEncoder#encode}). It does not include the 4-byte header itself.</p>
 *
 * <p>This class does not understand message semantics. It operates purely
 * on opaque byte arrays.</p>
 */
public final class FrameEncoder {

    /**
     * Maximum allowed frame size: 16 MB.
     * Generous for a chat protocol; prevents uncontrolled memory allocation
     * from a malicious or malformed length prefix.
     */
    public static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    private static final int HEADER_SIZE = 4;

    private FrameEncoder() {
        // Utility class
    }

    /**
     * Wraps an encoded message in a length-prefixed frame.
     *
     * @param encodedMessage the output of {@link MessageEncoder#encode}
     * @return the framed byte array ready for TCP transmission
     * @throws NullPointerException if encodedMessage is null
     * @throws ProtocolException if the message is empty or exceeds MAX_FRAME_SIZE
     */
    public static byte[] encode(byte[] encodedMessage) {
        Objects.requireNonNull(encodedMessage, "encodedMessage must not be null");

        if (encodedMessage.length == 0) {
            throw new ProtocolException("Cannot frame a zero-length encoded message");
        }
        if (encodedMessage.length > MAX_FRAME_SIZE) {
            throw new ProtocolException(
                    "Encoded message size " + encodedMessage.length
                    + " exceeds maximum frame size " + MAX_FRAME_SIZE);
        }

        byte[] frame = new byte[HEADER_SIZE + encodedMessage.length];

        // Big-endian length prefix
        frame[0] = (byte) (encodedMessage.length >>> 24);
        frame[1] = (byte) (encodedMessage.length >>> 16);
        frame[2] = (byte) (encodedMessage.length >>> 8);
        frame[3] = (byte) (encodedMessage.length);

        System.arraycopy(encodedMessage, 0, frame, HEADER_SIZE, encodedMessage.length);

        return frame;
    }
}