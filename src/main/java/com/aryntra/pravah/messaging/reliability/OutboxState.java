package com.aryntra.pravah.messaging.reliability;

/**
 * States representing the lifecycle of a delivery work item in the outbox.
 *
 * This is intentionally separate from MessageState.
 * MessageState tracks the logical message lifecycle (CREATED → SENT → DELIVERED).
 * OutboxState tracks the delivery work lifecycle (PENDING → COMPLETED).
 *
 * A message may be FAILED in MessageState while still PENDING in the outbox,
 * meaning the delivery intent survives a transient routing failure.
 */
public enum OutboxState {
    /** Delivery has not yet been attempted or a previous attempt failed. */
    PENDING,

    /** Delivery has been successfully completed (message routed to peer). */
    COMPLETED
}
