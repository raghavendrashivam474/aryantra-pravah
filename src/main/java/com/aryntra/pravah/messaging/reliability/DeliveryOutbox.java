package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.peer.PeerId;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction for tracking pending delivery work.
 *
 * The DeliveryOutbox represents the set of messages that the application
 * intends to deliver but have not yet been successfully routed.
 *
 * Conceptual separation from MessageHistoryStore:
 *   MessageHistoryStore = "What messages do I know about?"
 *   DeliveryOutbox      = "Which messages still need delivery work?"
 *
 * A message may exist in history while simultaneously being PENDING
 * in the outbox. Once delivery succeeds, the outbox entry is completed
 * and removed, while the history record persists.
 *
 * Implementations must use PeerId for destination identity,
 * never connectionId or transport metadata.
 */
public interface DeliveryOutbox {

    /**
     * Registers a new delivery intent.
     *
     * @param entry the outbox entry to enqueue
     * @throws IllegalArgumentException if an entry with the same messageId already exists
     * @throws NullPointerException if entry is null
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
     * from the pending set.
     *
     * @param messageId the message whose delivery completed
     */
    void markCompleted(String messageId);

    /**
     * Removes an outbox entry entirely.
     * No-op if the messageId does not exist.
     *
     * @param messageId the message to remove
     */
    void remove(String messageId);
}
