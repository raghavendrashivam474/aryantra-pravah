package com.aryntra.pravah.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Strict parser for reconstructing Pravah messages from raw binary data.
 */
public final class MessageParser {

    private MessageParser() {
        // Utility class
    }

    /**
     * Reconstructs a Message from its raw binary representation.
     *
     * @param data the full binary wire representation of a single message
     * @return the reconstructed logical Message
     * @throws NullPointerException if data is null
     * @throws ProtocolException if the data is malformed, truncated, has a bad version, or invalid structures
     */
    public static Message parse(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");

        // The minimum message size:
        // MAGIC (2) + VERSION (1) + TYPE (1) + SENDER_LEN (2) + MSG_LEN (2) + PAYLOAD_LEN (4) = 12 bytes
        if (data.length < 12) {
            throw new ProtocolException("Data is truncated; smaller than minimal message size of 12 bytes");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {

            // Read & check Magic
            byte m1 = dis.readByte();
            byte m2 = dis.readByte();
            if (m1 != MessageEncoder.MAGIC[0] || m2 != MessageEncoder.MAGIC[1]) {
                throw new ProtocolException(String.format(
                        "Invalid magic header: expected 'PR' (0x50, 0x52) but got 0x%02X, 0x%02X", m1, m2));
            }

            // Read & check Version
            byte version = dis.readByte();
            if (version != MessageEncoder.VERSION) {
                throw new ProtocolException(String.format(
                        "Unsupported protocol version: expected 0x%02X but got 0x%02X",
                        MessageEncoder.VERSION, version));
            }

            // Read & resolve Message Type
            byte typeCode = dis.readByte();
            MessageType type;
            try {
                type = MessageType.fromCode(typeCode);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException("Invalid message type code: " + e.getMessage(), e);
            }

            // Read Sender ID
            int senderLen = dis.readUnsignedShort();
            byte[] senderBytes = new byte[senderLen];
            dis.readFully(senderBytes);
            String senderId = new String(senderBytes, StandardCharsets.UTF_8);

            // Read Message ID
            int msgIdLen = dis.readUnsignedShort();
            byte[] msgIdBytes = new byte[msgIdLen];
            dis.readFully(msgIdBytes);
            String messageId = new String(msgIdBytes, StandardCharsets.UTF_8);

            // Read Payload
            int payloadLen = dis.readInt();
            if (payloadLen < 0) {
                throw new ProtocolException("Invalid negative payload length: " + payloadLen);
            }
            byte[] payload = new byte[payloadLen];
            dis.readFully(payload);

            // Strict verification: ensure there are no trailing unparsed bytes
            if (dis.available() > 0) {
                throw new ProtocolException("Trailing bytes detected beyond decoded message payload limits");
            }

            return new Message(type, senderId, messageId, payload);

        } catch (IOException e) {
            throw new ProtocolException("Failed to parse message due to truncated or corrupted input structure", e);
        }
    }
}