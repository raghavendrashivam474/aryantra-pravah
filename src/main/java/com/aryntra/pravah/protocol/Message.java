package com.aryntra.pravah.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable logical representation of a Pravah protocol message.
 *
 * <p>A Message is transport-independent: it contains no socket, stream,
 * or connection references. It represents <em>what</em> is being
 * communicated, not <em>how</em> bytes move between peers.</p>
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@code type}      — the protocol message type (JOIN, MESSAGE, LEAVE)</li>
 *   <li>{@code senderId}  — logical identity of the originating peer</li>
 *   <li>{@code messageId} — unique identifier for this logical message</li>
 *   <li>{@code payload}   — the message content as raw bytes</li>
 * </ul>
 *
 * <p>Equality is value-based and deterministic: two Messages with the
 * same type, senderId, messageId, and payload bytes are equal.</p>
 */
public record Message(
        MessageType type,
        String senderId,
        String messageId,
        byte[] payload
) {

    /**
     * Compact canonical constructor with validation and defensive copy.
     */
    public Message {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(senderId, "senderId must not be null");
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        if (senderId.isBlank()) {
            throw new IllegalArgumentException("senderId must not be blank");
        }
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }

        // Defensive copy: caller cannot mutate internal state
        payload = payload.clone();
    }

    /**
     * Returns a defensive copy of the payload.
     * External mutation of the returned array does not affect this Message.
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Value-based equality using Arrays.equals for the payload.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message m)) return false;
        return type == m.type
                && senderId.equals(m.senderId)
                && messageId.equals(m.messageId)
                && Arrays.equals(payload, m.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, senderId, messageId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "Message[type=" + type
                + ", senderId=" + senderId
                + ", messageId=" + messageId
                + ", payloadBytes=" + payload.length + "]";
    }
}