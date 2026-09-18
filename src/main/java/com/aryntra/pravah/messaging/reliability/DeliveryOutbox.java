package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.peer.PeerId;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction for tracking pending delivery work.
 *
 * The DeliveryOutbox represents the set of messages that the application
 * intends to deliver but have not yet been successfully routed.
 */
public interface DeliveryOutbox {

    /**
     * Registers a new delivery intent.
     */
    void enqueue(OutboxEntry entry);

    /**
     * Returns all entries currently in PENDING state,
     * ordered by creation time (oldest first).
     */
    List<OutboxEntry> findPending();

    /**
     * Returns all PENDING entries for a specific destination peer,
     * ordered by creation time (oldest first).
     */
    List<OutboxEntry> findPendingForPeer(PeerId destination);

    /**
     * Finds an outbox entry by its messageId, regardless of state.
     */
    Optional<OutboxEntry> findByMessageId(String messageId);

    /**
     * Marks a delivery as successfully completed and removes it
     * from the pending set (state becomes COMPLETED).
     */
    void markCompleted(String messageId);

    /**
     * Removes an outbox entry entirely.
     */
    void remove(String messageId);

    /**
     * Updates the attempt count for an existing entry.
     */
    void updateAttemptCount(String messageId, int attemptCount);

    /**
     * Updates the state for an existing entry (e.g. to ABANDONED).
     */
    void updateState(String messageId, OutboxState state);
}