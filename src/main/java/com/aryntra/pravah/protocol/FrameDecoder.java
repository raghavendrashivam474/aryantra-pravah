package com.aryntra.pravah.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stateful decoder that extracts complete encoded messages from an
 * arbitrary TCP byte stream using length-prefixed framing.
 *
 * <p>Each TCP connection must have its own {@code FrameDecoder} instance.
 * Never share a single decoder across multiple connections.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * FrameDecoder decoder = new FrameDecoder();
 * // For each TCP read chunk:
 * List&lt;byte[]&gt; messages = decoder.feed(tcpChunk);
 * for (byte[] msg : messages) {
 *     Message m = MessageParser.parse(msg);
 * }
 * </pre>
 *
 * <h3>Frame Format (must match {@link FrameEncoder})</h3>
 * <pre>
 * [FRAME_LEN: 4 bytes, big-endian][ENCODED_MESSAGE: FRAME_LEN bytes]
 * </pre>
 */
public final class FrameDecoder {

    private static final int HEADER_SIZE = 4;

    private byte[] buffer = new byte[0];

    /**
     * Feeds a chunk of raw TCP data into the decoder.
     * Returns zero or more complete encoded messages extracted from
     * the accumulated stream.
     *
     * @param chunk the raw bytes received from a single TCP read
     * @return a list of complete encoded message byte arrays (may be empty)
     * @throws NullPointerException if chunk is null
     * @throws ProtocolException if a frame length is invalid or exceeds the maximum
     */
    public List<byte[]> feed(byte[] chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");

        if (chunk.length == 0) {
            return Collections.emptyList();
        }

        // Append incoming chunk to internal buffer
        byte[] newBuffer = new byte[buffer.length + chunk.length];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        System.arraycopy(chunk, 0, newBuffer, buffer.length, chunk.length);
        buffer = newBuffer;

        List<byte[]> messages = new ArrayList<>();

        while (buffer.length >= HEADER_SIZE) {
            // Read 4-byte big-endian frame length (unsigned)
            int frameLen = ((buffer[0] & 0xFF) << 24)
                         | ((buffer[1] & 0xFF) << 16)
                         | ((buffer[2] & 0xFF) << 8)
                         | (buffer[3] & 0xFF);

            if (frameLen <= 0) {
                throw new ProtocolException("Invalid frame length: " + frameLen);
            }
            if (frameLen > FrameEncoder.MAX_FRAME_SIZE) {
                throw new ProtocolException(
                        "Frame length " + frameLen
                        + " exceeds maximum allowed size " + FrameEncoder.MAX_FRAME_SIZE);
            }

            int totalNeeded = HEADER_SIZE + frameLen;
            if (buffer.length < totalNeeded) {
                break; // Incomplete frame; wait for more data
            }

            // Extract the complete encoded message
            byte[] message = new byte[frameLen];
            System.arraycopy(buffer, HEADER_SIZE, message, 0, frameLen);
            messages.add(message);

            // Remove consumed bytes from buffer
            int remaining = buffer.length - totalNeeded;
            if (remaining == 0) {
                buffer = new byte[0];
            } else {
                byte[] tail = new byte[remaining];
                System.arraycopy(buffer, totalNeeded, tail, 0, remaining);
                buffer = tail;
            }
        }

        return messages;
    }

    /**
     * Resets the decoder state, discarding any buffered partial frame data.
     * Call this when a connection is closed or reset.
     */
    public void reset() {
        buffer = new byte[0];
    }

    /**
     * Returns the number of bytes currently buffered (partial frame data).
     * Useful for diagnostics and testing.
     */
    public int getBufferedBytes() {
        return buffer.length;
    }
}