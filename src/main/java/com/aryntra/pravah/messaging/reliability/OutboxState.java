package com.aryntra.pravah.messaging.reliability;

/**
 * States representing the lifecycle of a delivery work item in the outbox.
 *
 * This is intentionally separate from MessageState.
 * MessageState tracks the logical message lifecycle (CREATED → SENT → DELIVERED).
 * OutboxState tracks the delivery work lifecycle (PENDING → COMPLETED | ABANDONED).
 *
 * A message may be FAILED in MessageState while still PENDING in the outbox,
 * meaning the delivery intent survives a transient routing failure.
 *
 * ABANDONED indicates that the bounded retry policy has been exhausted
 * and no further automatic delivery attempts will be made.
 */
public enum OutboxState {
    /** Delivery has not yet been attempted or a previous attempt failed but retries remain. */
    PENDING,

    /** Delivery has been successfully completed (ACK received). */
    COMPLETED,

    /** Retry policy exhausted. No further automatic delivery attempts will be made. */
    ABANDONED
}