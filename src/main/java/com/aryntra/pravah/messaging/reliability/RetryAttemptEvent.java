package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.peer.PeerId;
import java.util.Objects;

/**
 * Immutable event representing a single automatic delivery retry attempt.
 */
public final class RetryAttemptEvent {

    public enum Outcome {
        /** The retry message was successfully serialized and routed to the underlying transport. */
        DISPATCHED,

        /** The retry routing or transport write failed. */
        FAILED,

        /** The retry failed and the maximum attempts threshold has been reached. */
        ABANDONED
    }

    private final String messageId;
    private final PeerId destination;
    private final int attemptCount;
    private final long timestamp;
    private final Outcome outcome;

    public RetryAttemptEvent(String messageId,
                             PeerId destination,
                             int attemptCount,
                             long timestamp,
                             Outcome outcome) {
        this.messageId = Objects.requireNonNull(messageId, "messageId must not be null");
        this.destination = Objects.requireNonNull(destination, "destination must not be null");
        this.attemptCount = attemptCount;
        this.timestamp = timestamp;
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public String messageId() { return messageId; }
    public PeerId destination() { return destination; }
    public int attemptCount() { return attemptCount; }
    public long timestamp() { return timestamp; }
    public Outcome outcome() { return outcome; }

    @Override
    public String toString() {
        return "RetryAttemptEvent{" +
                "msgId='" + messageId + '\'' +
                ", dest=" + destination.value() +
                ", attempt=" + attemptCount +
                ", outcome=" + outcome +
                '}';
    }
}