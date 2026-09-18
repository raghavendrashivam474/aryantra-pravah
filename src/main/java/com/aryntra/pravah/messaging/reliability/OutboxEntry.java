package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single unit of pending delivery work.
 *
 * An OutboxEntry captures the intent to deliver a specific message
 * to a specific destination peer. It is identified by messageId
 * and scoped by destination PeerId — never by connectionId or
 * transport metadata.
 *
 * The entry is immutable. State transitions are handled by the
 * DeliveryOutbox implementation, which replaces entries as needed.
 *
 * The attemptCount tracks how many delivery dispatch attempts have
 * been made. This supports bounded retry policies (S5.4).
 */
public final class OutboxEntry {

    private final String messageId;
    private final PeerId destination;
    private final ConversationId conversationId;
    private final OutboxState state;
    private final Instant createdAt;
    private final int attemptCount;

    /**
     * Full constructor including attempt count for bounded retry tracking.
     */
    public OutboxEntry(String messageId,
                       PeerId destination,
                       ConversationId conversationId,
                       OutboxState state,
                       Instant createdAt,
                       int attemptCount) {
        this.messageId = Objects.requireNonNull(messageId, "messageId must not be null");
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        this.destination = Objects.requireNonNull(destination, "destination must not be null");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        this.attemptCount = attemptCount;
    }

    /**
     * Backward-compatible constructor defaulting attemptCount to 0.
     */
    public OutboxEntry(String messageId,
                       PeerId destination,
                       ConversationId conversationId,
                       OutboxState state,
                       Instant createdAt) {
        this(messageId, destination, conversationId, state, createdAt, 0);
    }

    /**
     * Convenience constructor for creating a new PENDING entry with zero attempts.
     */
    public static OutboxEntry pending(String messageId,
                                      PeerId destination,
                                      ConversationId conversationId) {
        return new OutboxEntry(messageId, destination, conversationId,
                OutboxState.PENDING, Instant.now(), 0);
    }

    public String messageId() { return messageId; }
    public PeerId destination() { return destination; }
    public ConversationId conversationId() { return conversationId; }
    public OutboxState state() { return state; }
    public Instant createdAt() { return createdAt; }
    public int attemptCount() { return attemptCount; }

    /**
     * Returns a copy of this entry with a new state.
     */
    public OutboxEntry withState(OutboxState newState) {
        return new OutboxEntry(messageId, destination, conversationId,
                newState, createdAt, attemptCount);
    }

    /**
     * Returns a copy of this entry with an updated attempt count.
     */
    public OutboxEntry withAttemptCount(int newAttemptCount) {
        return new OutboxEntry(messageId, destination, conversationId,
                state, createdAt, newAttemptCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEntry that)) return false;
        return messageId.equals(that.messageId);
    }

    @Override
    public int hashCode() {
        return messageId.hashCode();
    }

    @Override
    public String toString() {
        return "OutboxEntry{messageId='" + messageId + "', dest=" + destination
                + ", state=" + state + ", attempts=" + attemptCount + "}";
    }
}