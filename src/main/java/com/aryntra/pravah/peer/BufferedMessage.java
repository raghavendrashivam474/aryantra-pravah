package com.aryntra.pravah.peer;

import com.aryntra.pravah.protocol.Message;
import java.util.Objects;

/**
 * Immutable record representing a message held in the transition-window buffer.
 *
 * <p>Captures the destination, the already-framed payload, enqueue time, and
 * a computed expiry deadline. The buffer uses this to enforce TTL and ordering.</p>
 *
 * B.R2 — Bounded In-Flight Buffering & Transition Reliability
 */
public record BufferedMessage(
        PeerId destination,
        Message originalMessage,
        byte[] framedPayload,
        long enqueuedAtMillis,
        long expiresAtMillis
) {
    public BufferedMessage {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(originalMessage, "originalMessage must not be null");
        Objects.requireNonNull(framedPayload, "framedPayload must not be null");
        if (framedPayload.length == 0) {
            throw new IllegalArgumentException("framedPayload must not be empty");
        }
        if (expiresAtMillis <= enqueuedAtMillis) {
            throw new IllegalArgumentException("expiresAtMillis must be after enqueuedAtMillis");
        }
    }

    /**
     * Returns true if this buffered message has exceeded its TTL.
     */
    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    /**
     * Returns the age of this buffered message in milliseconds.
     */
    public long ageMillis(long nowMillis) {
        return nowMillis - enqueuedAtMillis;
    }
}