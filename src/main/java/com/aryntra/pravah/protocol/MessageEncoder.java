package com.aryntra.pravah.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Deterministic encoder for Pravah messages.
 * Encodes a {@link Message} into its exact, raw binary representation.
 */
public final class MessageEncoder {

    public static final byte[] MAGIC = {'P', 'R'};
    public static final byte VERSION = 0x01;

    private MessageEncoder() {
        // Utility class
    }

    /**
     * Encodes a Message into a byte array.
     *
     * @param message the logical Message to encode
     * @return the exact deterministic byte representation of the message
     * @throws NullPointerException if the message is null
     * @throws ProtocolException if the message is invalid or cannot be encoded
     */
    public static byte[] encode(Message message) {
        Objects.requireNonNull(message, "message must not be null");

        byte[] senderBytes = message.senderId().getBytes(StandardCharsets.UTF_8);
        byte[] messageIdBytes = message.messageId().getBytes(StandardCharsets.UTF_8);
        byte[] payload = message.payload();

        if (senderBytes.length > 65535) {
            throw new ProtocolException("senderId exceeds maximum byte length of 65535");
        }
        if (messageIdBytes.length > 65535) {
            throw new ProtocolException("messageId exceeds maximum byte length of 65535");
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // Magic & Version
            dos.write(MAGIC);
            dos.write(VERSION);

            // Message Type Code
            dos.write(message.type().code());

            // Sender ID
            dos.writeShort(senderBytes.length);
            dos.write(senderBytes);

            // Message ID
            dos.writeShort(messageIdBytes.length);
            dos.write(messageIdBytes);

            // Payload
            dos.writeInt(payload.length);
            dos.write(payload);

            dos.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ProtocolException("Failed to encode message due to internal buffer error", e);
        }
    }
}